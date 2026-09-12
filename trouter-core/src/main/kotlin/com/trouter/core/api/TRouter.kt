package com.trouter.core.api

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.trouter.core.internal.FragmentContainerActivity
import com.trouter.core.internal.RemoteRouter
import com.trouter.core.internal.RouteTable
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * TRouter 统一入口（单例）。
 *
 * 统一治理：
 * - 统一入口：对外能力全部经本单例暴露；
 * - 统一配置：init 接收 [TRouterConfig]，行为全部可配置；
 * - 统一结果：navigate 返回 [TRouterResult]（密封类，无 null），强制处理失败分支；
 * - 统一契约：路径常量来自 [RouterContract]（KSP 层强制/告警）。
 *
 * 生命周期约定：
 * 1. [init]：核心初始化（配置/日志开关/onLost/空路由表），不感知任何生成代码；
 * 2. [install]：唯一装配入口，将某个 [GroupLoaderRegistry] 的分组加载器元数据登记进路由表；
 *    只登记「类名字符串」，不加载任何页面类（惰性，见 RouteMeta 注释）；
 * 3. [navigate]：按 path 查表并打开目标；页面类在此刻才真正加载。
 *
 * 可观测性（最早的基础版本 四个埋点，Timber，Tag=TRouter，消息为结构化 [节点] 行）：
 * navigate 入口/出口、GroupLoader 加载开始/结束；受 config.isDebug 控制。
 * logSink（测试收集器）与 logcat（Timber）收到的消息一致，无重复前缀。
 */
object TRouter {

    /** Timber 日志 Tag（logcat 过滤：adb logcat -s TRouter）。 */
    private const val LOG_TAG = "TRouter"

    /** 拦截器 Redirect 重定向累计跳数上限（防死循环，见 navigateInternal）。 */
    private const val MAX_REDIRECTS = 3

    /** 路径别名：别名注册表中正则别名前缀（exact 优先，其次按注册序正则匹配）。 */
    private const val ALIAS_REGEX_PREFIX = "regex:"

    @Volatile
    private var initialized = false
    private var appContext: Context? = null
    private var config: TRouterConfig = TRouterConfig()
    private val routeTable = RouteTable()

    // 目标 Class 按进程缓存（首次 navigate 时加载一次，后续命中缓存）
    private val classCache = java.util.concurrent.ConcurrentHashMap<String, Class<*>>()

    // 运行时拦截器注册表。读写均在锁内做「快照或原子替换」，navigate 每次取不可变快照：
    // 增删绝不打断进行中的链，只影响下一次 navigate（吸取「顺序/时机依赖」缺陷教训）。
    private val liveInterceptorsLock = Any()
    private val liveInterceptors = ArrayList<RouteChainMember>()

    // 目标级拦截器绑定表（name -> member）。CopyOnWrite 快照语义与 liveInterceptors 一致。
    private val targetBindingsLock = Any()
    private val targetBindings = LinkedHashMap<String, RouteChainMember>()

    // 路由别名表（alias -> 真实 path；支持 regex: 前缀正则别名）
    private val aliasLock = Any()
    private val aliasTable = LinkedHashMap<String, String>()

    // 进程内服务注册表（接口 Class -> 实现实例）
    private val servicesLock = Any()
    private val servicesTable = LinkedHashMap<Class<*>, Any>()

    // 跨进程服务端点注册表（名称 -> (Bundle)->String，各进程独立注册）
    private val endpointLock = Any()
    private val endpoints = LinkedHashMap<String, (Bundle) -> String>()
    private const val REMOTE_ERR_PREFIX = "-ERR"

    // F（动态路由热更/持久化）：动态注册路径集合（用于导出/恢复；静态路由不入集）
    private val dynamicPathsLock = Any()
    private val dynamicPaths = LinkedHashSet<String>()
    private val dynamicApplyLock = Any()

    // 生命周期绑定：追踪当前 resumed Activity，用于同任务内导航（返回键可回到调用页）
    private var lifecycleApp: Application? = null
    private var activityListener: Application.ActivityLifecycleCallbacks? = null
    private var resumedActivity: WeakReference<Activity>? = null
    private var timberPlanted = false

    // host 侧跨进程通道客户端（按需 bind，reset 时断开）。
    // **每个目标进程一个客户端实例**——连接/队列状态按进程隔离，互不干扰。
    private val remoteRoutersLock = Any()
    private val remoteRouters = LinkedHashMap<String, RemoteRouter>()

    private fun remoteRouterFor(component: ComponentName?, target: String?): RemoteRouter {
        val key = component?.flattenToString() ?: "default:${target ?: "-"}"
        return synchronized(remoteRoutersLock) {
            remoteRouters.getOrPut(key) { RemoteRouter() }
        }
    }

    // 异步链的恢复执行与结果回调统一回主线程（打开页面必须主线程）
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 统一初始化入口。可重复调用（后调用覆盖前一次），
     * 但路由表不会自动清空 —— 测试环境请先调用 resetForTest()。
     */
    fun init(context: Context, config: TRouterConfig) {
        val app = context.applicationContext
        this.appContext = app
        this.config = config
        synchronized(liveInterceptorsLock) {
            liveInterceptors.clear()
            liveInterceptors.addAll(config.interceptors)
        }
        this.initialized = true
        // Timber：进程内只 plant 一次
        if (!timberPlanted) {
            timberPlanted = true
            Timber.plant(Timber.DebugTree())
        }
        val application = app as? Application
        if (application != null && lifecycleApp !== application) {
            lifecycleApp?.unregisterActivityLifecycleCallbacks(activityListener)
            val listener = createLifecycleListener()
            application.registerActivityLifecycleCallbacks(listener)
            activityListener = listener
            lifecycleApp = application
        }
    }

    private fun createLifecycleListener(): Application.ActivityLifecycleCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() === activity) resumedActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }

    /** @return 当前 resumed Activity（可能为 null，如无前台 UI）。 */
    private fun currentActivity(): Activity? = resumedActivity?.get()

    /**
     * 路由装配（唯一装配入口，须在 [init] 之后调用）。
     * 只登记元数据（类名字符串），不加载任何页面类。
     */
    fun install(registry: GroupLoaderRegistry) {
        check(initialized) { "TRouter.init(context, config) 必须先于 install 调用" }
        var added = 0
        for (loader in registry.loaders()) {
            // 埋点：GroupLoader 加载开始
            log("[GroupLoader][load][start] group=${loader.group}")
            val startMs = SystemClock.elapsedRealtime()
            val metas = loader.routeMetas()
            for (meta in metas) {
                if (routeTable.register(meta)) {
                    added++
                } else {
                    // 多模块版本 可观测防线：重复 path（含跨模块/重复 install）如实告警，first-wins 语义固化
                    val keep = routeTable.find(meta.path)
                    log("[RouteTable][duplicate] path=${meta.path} keep=${keep?.targetClassName} incoming=${meta.targetClassName}")
                }
            }
            val cost = SystemClock.elapsedRealtime() - startMs
            // 埋点：GroupLoader 加载结束
            log("[GroupLoader][load][end] group=${loader.group} routes=${metas.size} added=$added costMs=$cost")
        }
    }

    // ------------------------------------------------------------------ 导航

    /**
     * 统一导航入口。返回 [TRouterResult]，不允许 null。
     *
     * @param path  必须来自 RouterContract 常量
     * @param bundle 目标页参数（Activity：作为 Intent extras；Fragment：随 Fragment 参数传递）
     *
     * 命中路由后先走 config.interceptors 拦截链（解析后、打开前）；
     * 决策为 Redirect 时以同 traceId 重入（跳数上限 MAX_REDIRECTS，防死循环）。
     */
    fun navigate(path: String, bundle: Bundle? = null): TRouterResult {
        if (!initialized) return TRouterResult.NotInitialized
        val traceId = UUID.randomUUID().toString().replace("-", "").take(8)
        return navigateInternal(path, bundle, traceId, redirectHop = 0, requestCode = null)
    }

    /**
     * 带结果回调的导航（拿页面返回值）：以 startActivityForResult 发起，结果由调用方 Activity 的
     * **onActivityResult** 接收（与系统语义一致）；无前台 Activity 时返回 Blocked。
     *
     * ⚠️ 这是**老式**回调路径：`registerForActivityResult` 注册出来的 launcher **收不到**这里的结果
     * （launcher 只接收自己发起的请求）。想用现代 Activity Result API，请用 [buildIntent] 拿到 Intent
     * 自己 `launcher.launch(intent)`；链上需要等待（异步拦截器）时用 [buildIntentAsync]。
     */
    fun navigateForResult(path: String, requestCode: Int, bundle: Bundle? = null): TRouterResult {
        if (!initialized) return TRouterResult.NotInitialized
        if (currentActivity() == null) {
            return TRouterResult.Blocked(path, "navigateForResult 需要前台 Activity（当前无 resumed Activity）")
        }
        val traceId = UUID.randomUUID().toString().replace("-", "").take(8)
        return navigateInternal(path, bundle, traceId, redirectHop = 0, requestCode = requestCode)
    }

    /**
     * 异步导航（异步拦截器改造）：链中允许出现 [AsyncInterceptor]，结果通过 [onResult] 回调（主线程，只回调一次）。
     *
     * 与同步 [navigate] 的差异：
     * - 异步拦截器可在任意线程延后终止本轮（proceed/block/redirect），剩余链与打开目标由框架切回主线程执行；
     * - 超过 `TRouterConfig.asyncInterceptorTimeoutMs` 未终止 → `Blocked`（reason 含超时信息）；
     * - 返回 [RouteRequest] 可取消：取消后迟到的 proceed 不会打开目标。
     *
     * @return 取消句柄（无需取消时忽略即可）
     */
    fun navigateAsync(path: String, bundle: Bundle? = null, onResult: (TRouterResult) -> Unit): RouteRequest {
        val delivered = AtomicBoolean(false)
        fun deliver(result: TRouterResult) {
            if (delivered.compareAndSet(false, true)) runOnMain { onResult(result) }
        }

        if (!initialized) {
            deliver(TRouterResult.NotInitialized)
            return RouteRequest {}
        }
        val traceId = UUID.randomUUID().toString().replace("-", "").take(8)
        val request = RouteRequest {
            log("[navigate][cancel] traceId=$traceId path=$path")
            deliver(TRouterResult.Blocked(path, "导航已取消（RouteRequest.cancel）"))
        }
        // 整条链统一在主线程起步；异步成员恢复时再切回主线程（见 AsyncChainRun.await）
        runOnMain {
            runNavigate(
                path = path,
                bundle = bundle,
                traceId = traceId,
                redirectHop = 0,
                requestCode = null,
                allowAsync = true,
                cancelled = { request.isCancelled },
                captureIntent = null,
                finish = ::deliver,
            )
        }
        return request
    }

    /**
     * **只解析、只产出 Intent**，不启动任何页面（见 [TRouterIntent]）。
     *
     * 与 [navigate] 的语义完全一致（同步）：别名、重定向、拦截器、`onLost`、元数据写入全都照跑，
     * 唯一区别是**链末端不调用 startActivity**，而是把造好的 Intent 交回来。因此现代写法可以直接用：
     *
     * ```
     * private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
     *     if (r.resultCode == Activity.RESULT_OK) { /* r.data */ }
     * }
     *
     * when (val r = TRouter.buildIntent(RouterContract.PATH_RESULT_DEMO)) {
     *     is TRouterIntent.Ready        -> pick.launch(r.intent)     // Intent 在你自己手里
     *     is TRouterIntent.Blocked      -> toast(r.reason)           // 登录校验/灰度照样生效
     *     is TRouterIntent.NotFound     -> toast("没有这个页面")
     *     TRouterIntent.NotInitialized  -> toast("忘了 init")
     * }
     * ```
     *
     * 不需要前台 Activity（只造 Intent、不启动）；链上若挂着**需要等待**的异步拦截器，
     * 返回 [TRouterIntent.Blocked]（提示改用 [buildIntentAsync]）——绝不阻塞主线程。
     */
    fun buildIntent(path: String, bundle: Bundle? = null): TRouterIntent {
        if (!initialized) return TRouterIntent.NotInitialized
        val holder = arrayOfNulls<Intent>(1)
        val result = navigateInternal(
            path = path,
            bundle = bundle,
            traceId = newTraceId(),
            redirectHop = 0,
            requestCode = null,
            captureIntent = { holder[0] = it },
        )
        return toTRouterIntent(result, holder[0])
    }

    /**
     * [buildIntent] 的异步版（异步拦截器改造）：链上出现**需要等待**的异步拦截器时用这个，
     * 否则会像同步导航一样被明确拒绝。
     *
     * 回调恒在主线程、只回调一次；返回 [RouteRequest] 可取消（取消后迟到的放行不会产出 Intent）。
     */
    fun buildIntentAsync(path: String, bundle: Bundle? = null, onResult: (TRouterIntent) -> Unit): RouteRequest {
        val delivered = AtomicBoolean(false)
        val holder = arrayOfNulls<Intent>(1)
        fun deliver(result: TRouterIntent) {
            if (delivered.compareAndSet(false, true)) runOnMain { onResult(result) }
        }
        if (!initialized) {
            deliver(TRouterIntent.NotInitialized)
            return RouteRequest {}
        }
        val traceId = newTraceId()
        val request = RouteRequest {
            log("[buildIntent][cancel] traceId=$traceId path=$path")
            deliver(TRouterIntent.Blocked(path, "导航已取消（RouteRequest.cancel）"))
        }
        runOnMain {
            runNavigate(
                path = path,
                bundle = bundle,
                traceId = traceId,
                redirectHop = 0,
                requestCode = null,
                allowAsync = true,
                cancelled = { request.isCancelled },
                captureIntent = { holder[0] = it },
            ) { result -> deliver(toTRouterIntent(result, holder[0])) }
        }
        return request
    }

    /** 内部链结果 → 对外 [TRouterIntent]（Success 配上真正造出来的那个 Intent）。 */
    private fun toTRouterIntent(result: TRouterResult, intent: Intent?): TRouterIntent = when (result) {
        is TRouterResult.Success ->
            if (intent != null) TRouterIntent.Ready(result.meta, intent)
            else TRouterIntent.Blocked(result.meta.path, "未能产出 Intent（内部状态异常）")
        is TRouterResult.NotFound -> TRouterIntent.NotFound(result.path)
        is TRouterResult.Blocked -> TRouterIntent.Blocked(result.path, result.reason)
        TRouterResult.NotInitialized -> TRouterIntent.NotInitialized
    }

    private fun newTraceId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    /**
     * 单跳导航实现（Redirect 重入复用本方法，同一 traceId 贯穿各跳）。
     * 每跳独立记录 navigate 入口/出口日志，hop 标注跳数（0 = 用户首次导航）。
     */
    private fun navigateInternal(
        path: String,
        bundle: Bundle?,
        traceId: String,
        redirectHop: Int,
        requestCode: Int? = null,
        captureIntent: ((Intent) -> Unit)? = null,
    ): TRouterResult {
        var captured: TRouterResult? = null
        runNavigate(
            path = path,
            bundle = bundle,
            traceId = traceId,
            redirectHop = redirectHop,
            requestCode = requestCode,
            allowAsync = false,
            cancelled = { false },
            captureIntent = captureIntent,
        ) { captured = it }
        // allowAsync=false 时链不可能挂起：finish 必然在本次调用栈内同步完成
        return captured ?: TRouterResult.Blocked(path, "链执行异常：未产出结果")
    }

    /**
     * 单跳导航核心（异步拦截器改造 起为**同步/异步共用**）：解析 → 别名 → 拦截链 → 打开 → 结果映射
     * （Redirect 以同 traceId 重入，跳数上限 MAX_REDIRECTS）。
     *
     * 两种模式：
     * - [allowAsync]=false（同步 [navigate] / [navigateForResult]）：链中出现异步拦截器立即 Blocked，
     *   **绝不阻塞主线程等待**——宁可明确报错，也不制造卡顿；
     * - [allowAsync]=true（[navigateAsync]）：异步拦截器可在任意线程延后终止，剩余链与打开目标切回主线程。
     *
     * [finish] 通过内部 settled 守卫保证**只交付一次**结果（超时/取消/正常三选一）。
     */
    private fun runNavigate(
        path: String,
        bundle: Bundle?,
        traceId: String,
        redirectHop: Int,
        requestCode: Int?,
        allowAsync: Boolean,
        cancelled: () -> Boolean,
        captureIntent: ((Intent) -> Unit)?,
        finish: (TRouterResult) -> Unit,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        log("[navigate][entry] traceId=$traceId hop=$redirectHop path=$path bundleKeys=${bundle?.size() ?: 0}")

        if (cancelled()) {
            log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Blocked(path=$path, reason=已取消) costMs=0")
            finish(TRouterResult.Blocked(path, "导航已取消"))
            return
        }

        val meta = routeTable.find(path)
        if (meta == null) {
            // 未注册路径：先查 路径别名 别名（精确 -> 正则），命中则转向真实 path（记跳数防环）
            val resolved = resolveAlias(path)
            if (resolved == null) {
                val cost = SystemClock.elapsedRealtime() - startMs
                log("[navigate][exit] traceId=$traceId hop=$redirectHop result=NotFound(path=$path) costMs=$cost")
                config.onLost?.invoke(path)
                finish(TRouterResult.NotFound(path))
                return
            }
            if (redirectHop + 1 > MAX_REDIRECTS) {
                val cost = SystemClock.elapsedRealtime() - startMs
                val reason = "alias loop（>=$MAX_REDIRECTS 跳）: $path"
                log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Blocked(path=$path, reason=$reason) costMs=$cost")
                finish(TRouterResult.Blocked(path, reason))
                return
            }
            log("[route][alias] from=$path to=$resolved hop=$redirectHop")
            runNavigate(resolved, bundle, traceId, redirectHop + 1, requestCode, allowAsync, cancelled, captureIntent, finish)
            return
        }

        var settled = false
        val settle: (TRouterResult) -> Unit = { result ->
            if (!settled) {
                settled = true
                finish(result)
            }
        }

        /**
         * 打开失败统一收口（与 最早的基础版本 语义一致：Error 出口日志 + onLost + NotFound）。
         *
         * 抽成函数的原因（回测发现）：**同一次打开失败，链上有没有异步/包裹拦截器，结果不能不一样**。
         * 之前同步分支靠外层 try/catch 兜住，异步分支（拦截器延后放行、包裹拦截器 proceed）里抛出的
         * 打开失败却会被当成"拦截器自己出错"→ 返回 Blocked 且 onLost 不触发；
         * 更糟的是异步续跑发生在主线程 Handler 里，没人兜就会**直接崩掉 App**。
         * 现在三条路径（直接打开 / 同步链 / 异步续跑）都走这一个收口。
         */
        val handleOpenFailure: (Throwable) -> Unit = { e ->
            val cost = SystemClock.elapsedRealtime() - startMs
            log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Error(${e.javaClass.simpleName}: ${e.message}) costMs=$cost")
            config.onLost?.invoke(path)
            settle(TRouterResult.NotFound(path))
        }

        try {
            executeChain(meta, bundle, traceId, requestCode, allowAsync, cancelled, captureIntent, handleOpenFailure) { outcome ->
                val cost = SystemClock.elapsedRealtime() - startMs
                when (outcome) {
                    is ChainOutcome.Opened -> {
                        log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Success(meta=${outcome.meta.path}, kind=${outcome.meta.kind}) costMs=$cost")
                        settle(TRouterResult.Success(outcome.meta))
                    }
                    is ChainOutcome.Blocked -> {
                        log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Blocked(path=${outcome.path}, reason=${outcome.reason}) costMs=$cost")
                        settle(TRouterResult.Blocked(outcome.path, outcome.reason))
                    }
                    is ChainOutcome.Redirected -> {
                        if (redirectHop + 1 > MAX_REDIRECTS) {
                            val reason = "redirect loop（>=${MAX_REDIRECTS + 1} 跳）: ${outcome.targetPath}"
                            log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Blocked(path=${meta.path}, reason=$reason) costMs=$cost")
                            settle(TRouterResult.Blocked(meta.path, reason))
                        } else {
                            log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Redirect(target=${outcome.targetPath}) costMs=$cost")
                            runNavigate(outcome.targetPath, bundle, traceId, redirectHop + 1, requestCode, allowAsync, cancelled, captureIntent, settle)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // 打开/包裹期间抛出的异常统一按「打开失败」处理（Error 出口 + onLost），与 V1 语义一致
            handleOpenFailure(e)
        }
    }

    /**
     * 链执行入口（同步/异步共用）：取链快照 → 空链直接打开目标 → 否则按模式执行。
     * 链结果经 [onOutcome] 交付；同步模式保证返回前交付一次，异步模式可能延后交付。
     */
    private fun executeChain(
        meta: RouteMeta,
        bundle: Bundle?,
        traceId: String,
        requestCode: Int?,
        allowAsync: Boolean,
        cancelled: () -> Boolean,
        captureIntent: ((Intent) -> Unit)?,
        onOpenFailure: (Throwable) -> Unit,
        onOutcome: (ChainOutcome) -> Unit,
    ) {
        val members = combinedMembers(meta, traceId)
        if (members == null) {
            onOutcome(ChainOutcome.Blocked(meta.path, "目标拦截器未注册（@Interceptor names 需先 bindTargetInterceptor）"))
            return
        }
        if (members.isEmpty()) {
            onOutcome(ChainOutcome.Opened(openTarget(meta, bundle, traceId, requestCode, captureIntent)))
            return
        }

        val chainStartMs = SystemClock.elapsedRealtime()
        log("[interceptor][start] traceId=$traceId path=${meta.path} interceptors=${members.size}")

        var finished = false
        val once: (ChainOutcome) -> Unit = { outcome ->
            if (!finished) {
                finished = true
                log("[interceptor][end] traceId=$traceId decision=${describeOutcome(outcome)} costMs=${SystemClock.elapsedRealtime() - chainStartMs}")
                onOutcome(outcome)
            }
        }

        val handler = mainHandler
        val timeoutMs = config.asyncInterceptorTimeoutMs
        val runOnMainThread: (() -> Unit) -> Unit = { block ->
            if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
        }
        val runSync: (Int) -> ChainOutcome = { from ->
            stepChain(members, from, meta, bundle, traceId, requestCode, captureIntent)
        }
        val evalAtomic: (RouteInterceptor, Int) -> ChainOutcome? = { m, idx ->
            evalRouteInterceptor(m, meta, bundle, traceId, idx)
        }
        val evalWrap: (WrappingInterceptor, Int, () -> ChainOutcome) -> ChainOutcome = { m, idx, proceed ->
            evalWrappingInterceptor(m, meta, bundle, traceId, idx, proceed)
        }

        /**
         * 异步链执行器（局部实现）：把"等待拦截器决策"从同步调用栈改为回调推进。
         *
         * 对外承诺（与 AsyncInterceptor / AsyncChain 文档一致）：
         * - 每个异步成员必须三选一终止本轮（proceed / block / redirect），**单次有效**，重复调用抛 IllegalStateException；
         * - 超时兜底 [timeoutMs]：超时按 Blocked 收口，迟到的终止一律忽略；
         * - 调用方取消后，迟到的 proceed 不会打开目标；
         * - 异步成员可在任意线程终止，剩余链与打开目标由框架切回主线程；
         * - 链中可连续出现多个异步成员（各自独立超时窗口）；
         * - 洋葱包裹拦截器**不能跨异步成员**（其 proceed 是同步契约）：命中时给出明确 Blocked，而不是静默错乱。
         */
        class AsyncRunner {
            private var settled = false

            /** 后置观察回调（"放行之后"的逻辑；只能观察，不能改写结果）。 */
            private val postHooks = ArrayList<(ChainOutcome) -> Unit>()

            fun start() = step(0)

            private fun settle(outcome: ChainOutcome) {
                if (settled) return
                settled = true
                // 逆序执行后置观察：内层先收尾，符合洋葱语义
                for (i in postHooks.indices.reversed()) {
                    runCatching { postHooks[i](outcome) }
                }
                once(outcome)
            }

            /**
             * 链末端打开失败：不是"拦截器的错"，也不是"没找到路径"，而是**这条路径打不开**
             * （目标类加载不了、Activity 没在 manifest 声明等）。统一交回 [onOpenFailure] 收口。
             *
             * 必须在这里收的原因：异步续跑发生在主线程 Handler 里，
             * 让异常逃出去就是**未捕获异常 → App 崩溃**（回测已复现过的形态）。
             */
            private fun failOpen(t: Throwable) {
                if (settled) return
                settled = true
                onOpenFailure(t)
            }

            private fun step(index: Int) {
                if (settled) return
                if (index >= members.size) {
                    val opened = try {
                        openTarget(meta, bundle, traceId, requestCode, captureIntent)
                    } catch (t: Throwable) {
                        failOpen(t)
                        return
                    }
                    settle(ChainOutcome.Opened(opened))
                    return
                }
                when (val member = members[index]) {
                    is AsyncInterceptor -> await(member, index)
                    is RouteInterceptor ->
                        evalAtomic(member, index)?.let { settle(it) } ?: step(index + 1)
                    is WrappingInterceptor -> {
                        val asyncAfter = members.drop(index + 1).any { it is AsyncInterceptor }
                        if (asyncAfter) {
                            settle(
                                ChainOutcome.Blocked(
                                    meta.path,
                                    "洋葱拦截器不能包裹异步拦截器（${member.javaClass.simpleName} 之后存在 AsyncInterceptor）：" +
                                        "请把包裹逻辑改写为 AsyncInterceptor，或调整拦截器顺序",
                                ),
                            )
                        } else {
                            settle(evalWrap(member, index) { runSync(index + 1) })
                        }
                    }
                    else -> settle(ChainOutcome.Blocked(meta.path, "未知拦截器类型: ${member.javaClass.name}"))
                }
            }

            /** 等待异步成员终止：正常 / 拦截 / 改道 / 超时 / 取消 / 异常 六条路径都有明确收口。 */
            private fun await(member: AsyncInterceptor, index: Int) {
                // 匿名对象里这三个名字会与 AsyncChain 自身成员同名，先取别名再暴露
                val waitMeta = meta
                val waitBundle = bundle
                val waitTrace = traceId
                val terminated = AtomicBoolean(false)
                var timeoutRunnable: Runnable? = null

                fun terminate(outcome: ChainOutcome) {
                    if (!terminated.compareAndSet(false, true)) return
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    settle(outcome)
                }

                val chain = object : AsyncChain {
                    override val meta: RouteMeta get() = waitMeta
                    override val bundle: Bundle? get() = waitBundle
                    override val traceId: String get() = waitTrace
                    override val isCancelled: Boolean get() = cancelled()

                    override fun proceed(done: (ChainOutcome) -> Unit) {
                        if (!terminateOnce("proceed")) return
                        if (cancelled()) {
                            settle(ChainOutcome.Blocked(meta.path, "导航已取消（RouteRequest.cancel）"))
                            return
                        }
                        postHooks.add(done)
                        // 剩余链继续由同一执行器推进（可再遇异步成员）；恢复执行切回主线程。
                        // 续跑里的异常不能逃到主线程 Handler（会直接崩 App）：一律按打开失败收口。
                        runOnMainThread {
                            try {
                                step(index + 1)
                            } catch (t: Throwable) {
                                failOpen(t)
                            }
                        }
                    }

                    override fun block(reason: String) {
                        if (!terminateOnce("block")) return
                        settle(ChainOutcome.Blocked(meta.path, reason))
                    }

                    override fun redirect(targetPath: String) {
                        if (!terminateOnce("redirect")) return
                        settle(ChainOutcome.Redirected(targetPath))
                    }

                    /**
                     * 终止守卫，返回是否应当继续本次终止动作：
                     * - 已收口（超时/取消/先前已终止）→ 返回 false **静默忽略**：迟到放行可能发生在任意线程
                     *   甚至 Handler 回调里，抛错会直接炸掉调用方，与"迟到一律忽略"的对外承诺也不一致；
                     * - 同一成员重复终止（proceed 之后再 block/redirect）→ 抛 IllegalStateException，
                     *   这是接入方的配置错误，必须显性暴露。
                     */
                    private fun terminateOnce(action: String): Boolean {
                        if (settled) return false
                        if (!terminated.compareAndSet(false, true)) {
                            throw IllegalStateException(
                                "AsyncChain 只能终止一次（proceed/block/redirect 三选一），重复调用被拒绝：$action",
                            )
                        }
                        timeoutRunnable?.let { handler.removeCallbacks(it) }
                        return true
                    }
                }

                timeoutRunnable = Runnable {
                    if (terminated.compareAndSet(false, true)) {
                        log("[interceptor][async][timeout] traceId=$traceId class=${member.javaClass.simpleName} timeoutMs=$timeoutMs")
                        settle(
                            ChainOutcome.Blocked(
                                meta.path,
                                "异步拦截器超时（${timeoutMs}ms）: ${member.javaClass.simpleName}",
                            ),
                        )
                    }
                }
                log("[interceptor][async][wait] traceId=$traceId index=${index + 1} class=${member.javaClass.simpleName} timeoutMs=$timeoutMs")
                handler.postDelayed(timeoutRunnable, timeoutMs)

                try {
                    member.intercept(chain)
                } catch (e: Throwable) {
                    log("[interceptor][eval] traceId=$traceId error=${e.javaClass.simpleName}: ${e.message}")
                    terminate(ChainOutcome.Blocked(meta.path, "interceptor error: ${e.javaClass.simpleName}: ${e.message}"))
                }
            }
        }

        if (allowAsync) {
            AsyncRunner().start()
        } else {
            once(stepChain(members, 0, meta, bundle, traceId, requestCode, captureIntent))
        }
    }

    /**
     * 组装一次导航的链快照 = 全局运行时拦截器(运行中增删拦截器) + 目标级拦截器(只给某个页面挂拦截器，按 resolver 解析并按名取绑定)。
     * @return null 表示解析失败/存在未注册的目标拦截器名（调用方转为 Blocked）。
     */
    private fun combinedMembers(meta: RouteMeta, traceId: String): List<RouteChainMember>? {
        // 全局链按 priority 降序稳定排序（默认 0 = 保持声明顺序）
        val global = interceptorSnapshot().sortedByDescending { it.priority }
        val resolver = config.targetInterceptorResolver ?: return global
        val names = try {
            resolver.invoke(meta.targetClassName)
        } catch (e: Throwable) {
            log("[interceptor][target][error] traceId=$traceId class=${meta.targetClassName} ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (names.isEmpty()) return global

        val result = ArrayList<RouteChainMember>(global.size + names.size)
        result.addAll(global)
        val missing = ArrayList<String>()
        synchronized(targetBindingsLock) {
            for (name in names) {
                val member = targetBindings[name]
                if (member == null) missing.add(name) else result.add(member)
            }
        }
        if (missing.isNotEmpty()) {
            log("[interceptor][target][missing] traceId=$traceId class=${meta.targetClassName} names=$missing")
            return null
        }
        return result
    }

    /**
     * 同步导航中执行异步成员（关键兼容语义）：
     * 异步拦截器**若能立即放行**（不等待 IO，例如"开关关着就直接放行"），同步 navigate 照常可用；
     * 只有当它**延迟放行**（真正需要等待）时，才返回 Blocked 提示改用 navigateAsync——
     * 既守住"绝不阻塞主线程"的底线，也不误伤"链上挂了异步实现、但本次立即通过"的常见场景
     * （例如 :remote 进程的全局链）。
     *
     * 迟到终止（本函数已按"未立即放行"收口后才到达的 proceed/block/redirect）一律静默忽略，与超时语义一致。
     */
    private fun runAsyncMemberSynchronously(
        member: AsyncInterceptor,
        members: List<RouteChainMember>,
        index: Int,
        meta: RouteMeta,
        bundle: Bundle?,
        traceId: String,
        requestCode: Int?,
        captureIntent: ((Intent) -> Unit)? = null,
    ): ChainOutcome {
        // 匿名对象里的成员名会与 AsyncChain 同名，先取别名
        val waitMeta = meta
        val waitBundle = bundle
        val waitTrace = traceId

        var terminated = false
        var closed = false
        var outcome: ChainOutcome? = null
        // 标记"异常是从放行之后的剩余链（即打开目标）里冒出来的"——那种异常不属于拦截器，
        // 必须原样向上抛，由导航层按「打开失败」收口（Error 出口 + onLost + NotFound），
        // 否则同一次打开失败会因为链上挂没挂异步拦截器而给出两种完全不同的结果。
        var insideProceed = false

        fun once(action: String): Boolean {
            if (closed) return false // 已按"未立即放行"收口：迟到终止静默忽略
            if (terminated) {
                throw IllegalStateException(
                    "AsyncChain 只能终止一次（proceed/block/redirect 三选一），重复调用被拒绝：$action",
                )
            }
            terminated = true
            return true
        }

        val chain = object : AsyncChain {
            override val meta: RouteMeta get() = waitMeta
            override val bundle: Bundle? get() = waitBundle
            override val traceId: String get() = waitTrace
            override val isCancelled: Boolean get() = false

            override fun proceed(done: (ChainOutcome) -> Unit) {
                if (!once("proceed")) return
                // 注意：这里**不能**用 finally 复位 insideProceed——finally 会先于外层 catch 执行，
                // 复位之后外层就分不清"异常来自拦截器"还是"来自打开目标"了。
                insideProceed = true
                val rest = stepChain(members, index + 1, meta, bundle, traceId, requestCode, captureIntent)
                insideProceed = false
                runCatching { done(rest) }
                outcome = rest
            }

            override fun block(reason: String) {
                if (!once("block")) return
                outcome = ChainOutcome.Blocked(waitMeta.path, reason)
            }

            override fun redirect(targetPath: String) {
                if (!once("redirect")) return
                outcome = ChainOutcome.Redirected(targetPath)
            }
        }

        try {
            member.intercept(chain)
        } catch (e: Throwable) {
            if (insideProceed) {
                // 异常来自"放行之后的剩余链/打开目标"：不属于拦截器，交回导航层按打开失败收口
                insideProceed = false
                throw e
            }
            log("[interceptor][eval] traceId=$traceId error=${e.javaClass.simpleName}: ${e.message}")
            return ChainOutcome.Blocked(meta.path, "interceptor error: ${e.javaClass.simpleName}: ${e.message}")
        }

        val immediate = outcome
        if (immediate == null) {
            closed = true
            log("[interceptor][async][deferred-sync] traceId=$traceId class=${member.javaClass.simpleName}")
            // "怎么改"要按调用方用的是哪套 API 给：导航空手走 navigateAsync，只要 Intent 的走 buildIntentAsync
            val advice = if (captureIntent != null) {
                "请改用 TRouter.buildIntentAsync(path, bundle) { intentResult -> ... }"
            } else {
                "请改用 TRouter.navigateAsync(path, bundle) { result -> ... }"
            }
            return ChainOutcome.Blocked(
                meta.path,
                "异步拦截器 ${member.javaClass.simpleName} 在同步导航中没有立即放行（延迟放行会阻塞主线程，已被拒绝）：$advice",
            )
        }
        return immediate
    }

    /**
     * 递归推进（同步模式）：index 到达成员末尾 = 打开目标；否则按成员类型执行。
     * 异步拦截器在同步模式下**明确 Blocked**（提示改用 navigateAsync），不做任何等待。
     */
    private fun stepChain(
        members: List<RouteChainMember>,
        index: Int,
        meta: RouteMeta,
        bundle: Bundle?,
        traceId: String,
        requestCode: Int? = null,
        captureIntent: ((Intent) -> Unit)? = null,
    ): ChainOutcome {
        if (index >= members.size) return openTargetOutcome(meta, bundle, traceId, requestCode, captureIntent)

        return when (val member = members[index]) {
            is RouteInterceptor ->
                evalRouteInterceptor(member, meta, bundle, traceId, index)
                    ?: stepChain(members, index + 1, meta, bundle, traceId, requestCode, captureIntent)
            is WrappingInterceptor ->
                evalWrappingInterceptor(member, meta, bundle, traceId, index) {
                    stepChain(members, index + 1, meta, bundle, traceId, requestCode, captureIntent)
                }
            is AsyncInterceptor -> runAsyncMemberSynchronously(member, members, index, meta, bundle, traceId, requestCode, captureIntent)
            else -> ChainOutcome.Blocked(meta.path, "未知拦截器类型: ${member.javaClass.name}")
        }
    }

    /**
     * 原子拦截器求值（同步/异步共用）。
     * @return null = 放行到下一个成员；非 null = 该成员直接定案（Block / Redirect / 拦截器故障）
     */
    private fun evalRouteInterceptor(
        member: RouteInterceptor,
        meta: RouteMeta,
        bundle: Bundle?,
        traceId: String,
        index: Int,
    ): ChainOutcome? {
        val decision = try {
            member.intercept(meta, bundle)
        } catch (e: Throwable) {
            log("[interceptor][eval] traceId=$traceId error=${e.javaClass.simpleName}: ${e.message}")
            return ChainOutcome.Blocked(meta.path, "interceptor error: ${e.javaClass.simpleName}: ${e.message}")
        }
        log("[interceptor][eval] traceId=$traceId index=${index + 1} class=${member.javaClass.simpleName} decision=${describeDecision(decision)}")
        return when (decision) {
            InterceptorDecision.Continue -> null
            is InterceptorDecision.Block -> ChainOutcome.Blocked(meta.path, decision.reason)
            is InterceptorDecision.Redirect -> ChainOutcome.Redirected(decision.targetPath)
        }
    }

    /**
     * 洋葱包裹拦截器求值（同步/异步共用）：[proceed] 为"放行到剩余链"的同步句柄，
     * 同一 chain 实例只允许调用一次（重复调用抛错，防重复打开目标）。
     */
    private fun evalWrappingInterceptor(
        member: WrappingInterceptor,
        meta: RouteMeta,
        bundle: Bundle?,
        traceId: String,
        index: Int,
        proceed: () -> ChainOutcome,
    ): ChainOutcome {
        log("[interceptor][eval] traceId=$traceId index=${index + 1} class=${member.javaClass.simpleName} decision=Wrap")
        // 同 runAsyncMemberSynchronously：区分"拦截器自己抛的错"与"放行之后打开目标失败"
        var insideProceed = false
        val singleShotChain = object : InterceptorChain {
            override val meta: RouteMeta = meta
            override val bundle: Bundle? = bundle
            private var consumed = false

            override fun proceed(): ChainOutcome {
                if (consumed) {
                    throw IllegalStateException("InterceptorChain.proceed 只能调用一次（防止重复打开目标）")
                }
                consumed = true
                // 同 runAsyncMemberSynchronously：复位不能放 finally（否则外层 catch 分不清异常来源）
                insideProceed = true
                val outcome = proceed()
                insideProceed = false
                return outcome
            }
        }
        return try {
            member.intercept(singleShotChain)
        } catch (e: Throwable) {
            if (insideProceed) {
                insideProceed = false
                throw e
            }
            log("[interceptor][eval] traceId=$traceId error=${e.javaClass.simpleName}: ${e.message}")
            ChainOutcome.Blocked(meta.path, "interceptor error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 链末端：真正打开目标（异常向上冒泡，由 navigateInternal 按打开失败处理）。 */
    private fun openTargetOutcome(
        meta: RouteMeta,
        bundle: Bundle?,
        traceId: String,
        requestCode: Int? = null,
        captureIntent: ((Intent) -> Unit)? = null,
    ): ChainOutcome {
        val opened = openTarget(meta, bundle, traceId, requestCode, captureIntent)
        return ChainOutcome.Opened(opened)
    }

    /** 页面类缓存：目标类加载（带进程内缓存）；失败抛 ClassNotFoundException（由调用方按打开失败处理）。 */
    @Suppress("UNCHECKED_CAST")
    private fun loadTargetClass(className: String): Class<*> =
        classCache.computeIfAbsent(className) { Class.forName(it) }

    /**
     * 路由目标合法性校验（可观测/测试/构建辅助）。
     * 逐条尝试加载已注册路由的目标类，返回**无法加载**的路由清单（不含那些成功的）。
     * 静态路由目标类由 KSP 保证存在；本方法主要用于捕获**动态注册传错类名**这类迟发现问题。
     */
    fun checkRouteTargets(): List<RouteMeta> {
        if (!initialized) return emptyList()
        val missing = ArrayList<RouteMeta>()
        for (meta in routeTable.snapshot()) {
            try {
                loadTargetClass(meta.targetClassName)
            } catch (t: Throwable) {
                missing.add(meta)
                log("[route][verify][missing] path=${meta.path} target=${meta.targetClassName} error=${t.javaClass.simpleName}")
            }
        }
        return missing
    }

    private fun describeDecision(decision: InterceptorDecision): String = when (decision) {
        InterceptorDecision.Continue -> "Continue"
        is InterceptorDecision.Block -> "Block(reason=${decision.reason})"
        is InterceptorDecision.Redirect -> "Redirect(target=${decision.targetPath})"
    }

    private fun describeOutcome(outcome: ChainOutcome): String = when (outcome) {
        is ChainOutcome.Opened -> "Continue"
        is ChainOutcome.Blocked -> "Block(reason=${outcome.reason})"
        is ChainOutcome.Redirected -> "Redirect(target=${outcome.targetPath})"
    }

    // ------------------------------------------------------------------ 动态路由热更 / 持久化（F）

    /**
     * 导出当前**动态**路由（不含静态）——供持久化/热更下发。
     */
    fun exportDynamicRoutes(): List<RouteMeta> {
        if (!initialized) return emptyList()
        val paths = synchronized(dynamicPathsLock) { LinkedHashSet(dynamicPaths) }
        return routeTable.snapshot().filter { it.path in paths }
    }

    /**
     * 原子应用一批动态路由变更（热更）：removes 先移除、adds 再注册。
     * 任一条 add 与「移除后仍存在的路径 / 本批 add」重复、或字段非法 → **整批失败**（不改动任何状态）。
     * @return true 全量成功；false 校验失败或未初始化（此时无任何变更落地）。
     */
    fun applyRouteConfig(removes: List<String>, adds: List<RouteMeta>): Boolean {
        if (!initialized) return false
        synchronized(dynamicApplyLock) {
            // 1) 预校验（在“移除后剩余表 + 本批 add”上模拟，不触碰真实表）
            val planned = LinkedHashMap<String, RouteMeta>()
            routeTable.snapshot().forEach { planned[it.path] = it }
            val removeSet = LinkedHashSet(removes.filter { it.isNotBlank() })
            removeSet.forEach { planned.remove(it) }
            val seen = HashSet<String>()
            for (add in adds) {
                if (add.path.isBlank() || add.targetClassName.isBlank()) return false
                if (planned.containsKey(add.path) || !seen.add(add.path)) return false
                planned[add.path] = add
            }
            // 2) 落地
            for (p in removeSet) {
                if (routeTable.remove(p)) synchronized(dynamicPathsLock) { dynamicPaths.remove(p) }
            }
            for (add in adds) {
                if (routeTable.register(add)) synchronized(dynamicPathsLock) { dynamicPaths.add(add.path) }
            }
            log("[route][apply] removes=${removeSet.size} adds=${adds.size}")
            return true
        }
    }

    /** 持久化：把当前动态路由写入 [file]（UTF-8）。@return 是否写成功。 */
    fun saveDynamicRoutes(file: java.io.File): Boolean {
        if (!initialized) return false
        return try {
            file.writeText(DynamicRouteCodec.encode(exportDynamicRoutes()))
            log("[route][save] file=${file.name} routes=${exportDynamicRoutes().size}")
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 恢复：读取 [file]，用其内容**原子替换**当前动态路由集（先清当前动态、再导入）。 */
    fun loadDynamicRoutes(file: java.io.File): Boolean {
        if (!initialized) return false
        val adds = try {
            DynamicRouteCodec.decode(file.readText())
        } catch (t: Throwable) {
            return false
        }
        val removes = exportDynamicRoutes().map { it.path }
        return applyRouteConfig(removes = removes, adds = adds)
    }

    // ------------------------------------------------------------------ 路由表 JSON（整表导出导入）与服务层（模块间接口调用）

    /** 整表导出导入：把当前全部路由（静态+动态）导出为规范 JSON（供下发/审计）。 */
    fun exportRouteMapJson(): String = RouteMapCodec.toJson(registeredRoutes())

    /**
     * 导入路由表 JSON 作为**动态覆盖层**：先清当前动态路由，再原子注册导入内容；
     * 导入与静态路由冲突或格式非法 → 整批失败、零变更。
     * @return true = 导入并覆盖成功。
     */
    fun importRouteMapJson(text: String): Boolean {
        if (!initialized) return false
        val metas = RouteMapCodec.fromJson(text) ?: return false
        val removes = exportDynamicRoutes().map { it.path }
        return applyRouteConfig(removes = removes, adds = metas)
    }

    /** 模块间接口调用：注册进程内服务（接口 key → 实现实例）。重复接口返回 false。 */
    fun <T : Any> registerService(serviceClass: Class<T>, instance: T): Boolean {
        if (!initialized) return false
        val ok = synchronized(servicesLock) {
            if (servicesTable.containsKey(serviceClass)) false else {
                servicesTable[serviceClass] = instance
                true
            }
        }
        if (ok) log("[service][register] interface=${serviceClass.name} impl=${instance.javaClass.name}") else log("[service][register][conflict] interface=${serviceClass.name}")
        return ok
    }

    /** 模块间接口调用：注销进程内服务。@return true = 确有移除。 */
    fun unregisterService(serviceClass: Class<*>): Boolean {
        if (!initialized) return false
        val removed = synchronized(servicesLock) { servicesTable.remove(serviceClass) }
        if (removed != null) log("[service][unregister] interface=${serviceClass.name}")
        return removed != null
    }

    /** 模块间接口调用：按接口查找服务实例；未注册返回 null（调用方自行判空，不抛异常）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> findService(serviceClass: Class<T>): T? =
        synchronized(servicesLock) { servicesTable[serviceClass] as T? }

    /** 模块间接口调用：已注册服务接口快照。 */
    fun registeredServices(): List<Class<*>> =
        synchronized(servicesLock) { ArrayList(servicesTable.keys) }

    /**
     * 注册跨进程服务端点（各进程独立注册表；名称重复拒绝）。
     * 端点签名：入参 Bundle（基础类型）→ 返回结构化字符串（默认协议自定，host 原样回传）。
     */
    fun registerRemoteEndpoint(name: String, handler: (Bundle) -> String): Boolean {
        if (!initialized || name.isBlank()) return false
        val ok = synchronized(endpointLock) {
            if (endpoints.containsKey(name)) false else {
                endpoints[name] = handler
                true
            }
        }
        if (ok) log("[service][remote][register] name=$name") else log("[service][remote][register][conflict] name=$name")
        return ok
    }

    fun unregisterRemoteEndpoint(name: String): Boolean {
        if (!initialized) return false
        val removed = synchronized(endpointLock) { endpoints.remove(name) }
        if (removed != null) log("[service][remote][unregister] name=$name")
        return removed != null
    }

    /** 服务进程侧（RemoteRouterService 内）按名称调用本进程端点；未注册返回 [REMOTE_ERR_PREFIX] 串。 */
    fun invokeRemoteEndpoint(name: String, args: Bundle): String {
        val handler = synchronized(endpointLock) { endpoints[name] }
        if (handler == null) return REMOTE_ERR_PREFIX + " unregistered:" + name
        return try {
            handler.invoke(args)
        } catch (e: Throwable) {
            REMOTE_ERR_PREFIX + " " + e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    /** @return true 表示该串为服务错误（未注册/异常）。 */
    fun isRemoteEndpointError(reply: String): Boolean = reply.startsWith(REMOTE_ERR_PREFIX)


    // ------------------------------------------------------------------ 拦截器运行时增删（运行中增删拦截器）

    /**
     * 运行时追加拦截器（运行中增删拦截器）。立即对**下一次** navigate 生效（本次进行中的链不受影响）。
     * 同一实例重复注册返回 false（拒绝重复），并输出重复日志。
     * @return true = 已加入；false = 未初始化 或 重复实例。
     */
    fun addInterceptor(interceptor: RouteChainMember): Boolean {
        if (!initialized) return false
        val added = synchronized(liveInterceptorsLock) {
            if (liveInterceptors.any { it === interceptor }) false else {
                liveInterceptors.add(interceptor)
                true
            }
        }
        if (added) {
            log("[interceptor][register] class=${interceptor.javaClass.simpleName} count=${interceptorSnapshot().size}")
        } else {
            log("[interceptor][register][duplicate] class=${interceptor.javaClass.simpleName}")
        }
        return added
    }

    /**
     * 运行时移除拦截器（运行中增删拦截器，按实例引用）。
     * @return true = 确有移除；false = 未初始化 或 不存在。
     */
    fun removeInterceptor(interceptor: RouteChainMember): Boolean {
        if (!initialized) return false
        val removed = synchronized(liveInterceptorsLock) {
            val sizeBefore = liveInterceptors.size
            liveInterceptors.removeAll { it === interceptor }
            liveInterceptors.size < sizeBefore
        }
        if (removed) log("[interceptor][unregister] class=${interceptor.javaClass.simpleName} count=${interceptorSnapshot().size}")
        return removed
    }

    /** 已注册拦截器只读快照（运行中增删拦截器，可观测）。 */
    fun registeredInterceptors(): List<RouteChainMember> {
        if (!initialized) return emptyList()
        return interceptorSnapshot()
    }

    /** 拦截器链使用的不可变快照（单线程锁内拷贝；navigate 各次互不干扰）。 */
    private fun interceptorSnapshot(): List<RouteChainMember> =
        synchronized(liveInterceptorsLock) { ArrayList(liveInterceptors) }

    // ------------------------------------------------------------------ 目标级拦截器绑定（只给某个页面挂拦截器）

    /**
     * 把标识名绑定到拦截器实例（只给某个页面挂拦截器，与 @Interceptor(names) 配套）。
     * @return true 绑定成功；false = 未初始化 或 该 name 已被占用（需先 unbind）。
     */
    fun bindTargetInterceptor(name: String, interceptor: RouteChainMember): Boolean {
        if (!initialized) return false
        val bound = synchronized(targetBindingsLock) {
            if (targetBindings.containsKey(name)) false else {
                targetBindings[name] = interceptor
                true
            }
        }
        if (bound) {
            log("[interceptor][target][bind] name=$name class=${interceptor.javaClass.simpleName}")
        } else {
            log("[interceptor][target][bind][conflict] name=$name")
        }
        return bound
    }

    /** 解绑标识名（只给某个页面挂拦截器）。@return true = 确有解绑。 */
    fun unbindTargetInterceptor(name: String): Boolean {
        if (!initialized) return false
        val removed = synchronized(targetBindingsLock) { targetBindings.remove(name) }
        if (removed != null) log("[interceptor][target][unbind] name=$name")
        return removed != null
    }

    /** 已绑定目标拦截器只读快照（只给某个页面挂拦截器）。 */
    fun registeredTargetInterceptors(): Map<String, RouteChainMember> =
        synchronized(targetBindingsLock) { LinkedHashMap(targetBindings) }

    // ------------------------------------------------------------------ 深链（从外部链接进入）与路由别名（路径别名）

    /**
     * URI/Scheme 深链入口（从外部链接进入）：scheme 须在 config.deeplinkSchemes 白名单内；
     * uri.path 段即内部路由 path；query 并入导航参数（query 优先于入参 bundle）。
     *
     * scheme 比较**不区分大小写**（回测发现）：URI 的 scheme 按 RFC 3986 本身就不区分大小写，
     * Android 的 intent-filter 匹配也不区分；从浏览器/短信/扫码进来的链接大小写完全不受 App 控制，
     * 若按字面比较，`TROUTER://app/second` 这类链接会被白名单拒掉——表现是"链接点了没反应"。
     */
    fun navigateUri(uri: Uri, bundle: Bundle? = null): TRouterResult {
        if (!initialized) return TRouterResult.NotInitialized
        val scheme = uri.scheme
        val whitelist = config.deeplinkSchemes ?: emptySet()
        val allowed = !scheme.isNullOrEmpty() && whitelist.any { it.equals(scheme, ignoreCase = true) }
        if (!allowed) {
            return TRouterResult.Blocked(uri.toString(), "scheme 未启用（TRouterConfig.deeplinkSchemes 需包含 \"$scheme\"）")
        }
        val path = uri.path?.takeIf { it.isNotBlank() }
            ?: return TRouterResult.Blocked(uri.toString(), "URI 缺少路径段")
        val merged = Bundle()
        if (bundle != null) merged.putAll(bundle)
        merged.putAll(UriRouter.paramsOf(uri))
        return navigate(path, merged)
    }

    /**
     * 注册路由别名（路径别名）：alias 未注册时导航 alias 会转向 [toPath]。
     * alias 以 `regex:` 开头视为正则（精确别名优先，正则按注册序取首个命中）。
     * @return true 注册成功；false = 未初始化 / 空参数 / 别名重复。
     */
    fun registerRouteAlias(alias: String, toPath: String): Boolean {
        if (!initialized || alias.isBlank() || toPath.isBlank()) return false
        // 别名不得与已注册路由（静态/动态）同名：静态优先语义 → 静态命中时别名永不生效，故直接拒绝
        if (routeTable.find(alias) != null) {
            log("[route][alias][register][conflict] alias=$alias（与已注册路由同名）")
            return false
        }
        val ok = synchronized(aliasLock) {
            if (aliasTable.containsKey(alias)) false else {
                aliasTable[alias] = toPath
                true
            }
        }
        if (ok) log("[route][alias][register] alias=$alias to=$toPath") else log("[route][alias][register][conflict] alias=$alias")
        return ok
    }

    /** 注销路由别名（路径别名）。@return true = 确有移除。 */
    fun unregisterRouteAlias(alias: String): Boolean {
        if (!initialized) return false
        val removed = synchronized(aliasLock) { aliasTable.remove(alias) }
        if (removed != null) log("[route][alias][unregister] alias=$alias")
        return removed != null
    }

    /** 已注册别名只读快照（路径别名）。 */
    fun registeredRouteAliases(): Map<String, String> =
        synchronized(aliasLock) { LinkedHashMap(aliasTable) }

    private fun resolveAlias(path: String): String? {
        val snapshot = synchronized(aliasLock) { LinkedHashMap(aliasTable) }
        snapshot[path]?.let { return it }
        for ((alias, target) in snapshot) {
            if (!alias.startsWith(ALIAS_REGEX_PREFIX)) continue
            val patternText = alias.removePrefix(ALIAS_REGEX_PREFIX)
            try {
                if (Regex(patternText).matches(path)) return target
            } catch (ignored: Exception) {
                // 非法正则别名：跳过并交由注册期提示（此处不崩溃）
            }
        }
        return null
    }

    // ------------------------------------------------------------------ 动态路由与图谱（运行时注册路径版本）

    /**
     * 运行时注册路由（运行时注册路径版本）：不依赖 KSP 生成物，与静态路由**同表共存**。
     * 目标仍只存类名字符串（惰性），拦截器/降级/快照/图谱自动生效。
     *
     * @return true 注册成功；false = 未初始化 或 与既有 path 冲突（拒绝覆盖，先 unregister 再注册）
     */
    fun registerRoute(meta: RouteMeta): Boolean {
        if (!initialized) return false
        val registered = routeTable.register(meta)
        if (registered) {
            synchronized(dynamicPathsLock) { dynamicPaths.add(meta.path) }
            log("[route][register] path=${meta.path} group=${meta.group} kind=${meta.kind} target=${meta.targetClassName}")
        } else {
            val keep = routeTable.find(meta.path)
            log("[route][register][conflict] path=${meta.path} keep=${keep?.targetClassName} incoming=${meta.targetClassName}")
        }
        return registered
    }

    /**
     * 运行时注销路由（运行时注册路径版本）：静态与动态路由均可注销（路径级统一语义）。
     * @return true 表示确有移除；未初始化或不存在返回 false。
     */
    fun unregisterRoute(path: String): Boolean {
        if (!initialized) return false
        val removed = routeTable.remove(path)
        if (removed) {
            synchronized(dynamicPathsLock) { dynamicPaths.remove(path) }
            log("[route][unregister] path=$path")
        }
        return removed
    }

    /**
     * 路由图谱（运行时注册路径版本）：由当前路由表（静态 + 动态）实时导出，顺序确定（按注册/插入序）。
     */
    fun routeGraph(): RouteGraph {
        if (!initialized) return RouteGraph(nodes = emptyList(), edges = emptyList())
        val metas = routeTable.snapshot()
        val nodes = ArrayList<String>()
        val seenNode = LinkedHashSet<String>()
        val edges = ArrayList<RouteGraph.RouteGraphEdge>(metas.size)
        for (meta in metas) {
            if (seenNode.add(meta.targetClassName)) nodes.add(meta.targetClassName)
            edges.add(
                RouteGraph.RouteGraphEdge(
                    path = meta.path,
                    group = meta.group,
                    kind = meta.kind,
                    toClass = meta.targetClassName,
                ),
            )
        }
        return RouteGraph(nodes = nodes, edges = edges)
    }

    // ------------------------------------------------------------------ 跨进程导航（跨进程版本）

    /**
     * 跨进程导航：把导航请求经 AIDL 发给 remote 进程，由**远端进程自己的 TRouter** 解析并打开，
     * 结果摘要回传后重建为统一 [TRouterResult]，在主线程回调 [onResult]。
     *
     * @param path 必须来自 RouterContract 常量，且（配置了 remoteWhitelist 时）已标注 @CrossProcess
     * @param onResult 远端结果回调（主线程）；守卫/超时失败以 Blocked 表达，不触发 onLost
     *
     * 要求：config.remoteService 已配置且远端进程 Application 已 init/install（各进程独立路由表）。
     */
    fun navigateRemote(
        path: String,
        bundle: Bundle? = null,
        target: String? = null,
        onResult: (TRouterResult) -> Unit,
    ) {
        if (!initialized) {
            onResult(TRouterResult.NotInitialized)
            return
        }
        val ctx = appContext
        if (ctx == null) {
            onResult(TRouterResult.Blocked(path, "remote 通道未初始化（TRouter.init 未完成）"))
            return
        }
        val component = resolveRemoteComponent(target)
        if (target != null && component == null) {
            onResult(TRouterResult.Blocked(path, remoteTargetMissingReason(target)))
            return
        }
        // 注意：target=null 且 remoteService 为空时**继续下传**，由 RemoteRouter 按既有语义
        // 返回 Blocked 并输出 [remote][fail] 日志（保持"未配置"这一契约的文案与可观测性不变）
        remoteRouterFor(component, target).navigate(ctx, component, config.remoteWhitelist, path, bundle, onResult) {
            log(it)
        }
    }

    /** 解析目标进程的 AIDL 服务组件：target=null → 默认 [TRouterConfig.remoteService]；否则查 [TRouterConfig.remoteServices]。 */
    private fun resolveRemoteComponent(target: String?): ComponentName? =
        if (target == null) config.remoteService else config.remoteServices[target]

    private fun remoteTargetMissingReason(target: String?): String =
        "未配置 target=$target 的跨进程服务（请加入 TRouterConfig.remoteServices，为其声明 RemoteRouterService 子类，并在 manifest 指定 android:process）"

    /**
     * 跨进程调用远端进程注册的服务端点（结果原样 String 回传主线程；
     * 失败以 RemoteReplyCodec.SERVICE_ERROR_PREFIX 前缀串表达）。
     */
    fun callRemoteService(
        name: String,
        args: Bundle? = null,
        target: String? = null,
        onResult: (String) -> Unit,
    ) {
        if (!initialized) {
            onResult(REMOTE_ERR_PREFIX + "TRouter 未初始化")
            return
        }
        val ctx = appContext
        if (ctx == null) {
            onResult(REMOTE_ERR_PREFIX + "remote 通道未初始化（TRouter.init 未完成）")
            return
        }
        val component = resolveRemoteComponent(target)
        if (target != null && component == null) {
            onResult(REMOTE_ERR_PREFIX + " " + remoteTargetMissingReason(target))
            return
        }
        remoteRouterFor(component, target).callService(ctx, component, name, args, onResult) { log(it) }
    }

    // ------------------------------------------------------------------ 类型化远程 API（多进程与跨进程增强）

    /** 远端进程侧：api 名 → 分发器（由 @RemoteApi 生成物登记）。 */
    private val remoteApiLock = Any()
    private val remoteApiDispatchers = LinkedHashMap<String, (method: String, args: Bundle) -> Bundle>()

    /** 客户端进程侧：接口 Class → 编解码器。 */
    private val remoteApiCodecLock = Any()
    private val remoteApiCodecs = LinkedHashMap<Class<*>, TRouterRemoteApiCodec>()

    /**
     * 登记一个类型化远程 API 的实现（**在远端进程**调用，通常来自 Application）。
     * @return true 登记成功；false = 未初始化 / 名称为空 / 重名。
     */
    fun registerRemoteApi(name: String, dispatcher: (method: String, args: Bundle) -> Bundle): Boolean {
        if (!initialized || name.isBlank()) return false
        val ok = synchronized(remoteApiLock) {
            if (remoteApiDispatchers.containsKey(name)) {
                false
            } else {
                remoteApiDispatchers[name] = dispatcher
                true
            }
        }
        if (ok) log("[remote][typed][register] service=$name") else log("[remote][typed][register][conflict] service=$name")
        return ok
    }

    fun unregisterRemoteApi(name: String): Boolean {
        if (!initialized) return false
        val removed = synchronized(remoteApiLock) { remoteApiDispatchers.remove(name) }
        if (removed != null) log("[remote][typed][unregister] service=$name")
        return removed != null
    }

    /** 服务进程侧（RemoteRouterService 内）按名称分发类型化调用；未登记返回失败回包。 */
    fun invokeRemoteApi(name: String, method: String, args: Bundle): Bundle {
        val dispatcher = synchronized(remoteApiLock) { remoteApiDispatchers[name] }
            ?: return TRouterTypedReply.failure("远端未注册该 API：$name")
        return try {
            dispatcher.invoke(method, args)
        } catch (e: Throwable) {
            TRouterTypedReply.failure("${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    /**
     * 客户端进程侧注册编解码器（**在调用方进程**调用，通常一行：
     * `TRouter.registerRemoteApiClients(TRouterRemoteApiRegistry.all())`）。
     * @return 本次成功登记的个数
     */
    fun registerRemoteApiClients(codecs: List<TRouterRemoteApiCodec>): Int {
        if (!initialized) return 0
        var count = 0
        synchronized(remoteApiCodecLock) {
            for (codec in codecs) {
                remoteApiCodecs[codec.apiClass] = codec
                count++
            }
        }
        log("[remote][typed][client-register] count=$count")
        return count
    }

    /**
     * 取类型化远程 API 的动态代理（多进程与跨进程增强）：
     * ```
     * val api = TRouter.remoteApi(DemoStatsApi::class.java, target = "remote2") { err -> ... }
     * api.count("abc") { n -> statusBar("远端返回 $n") }   // 结果回主线程
     * ```
     * - 未注册编解码器 → 抛 [IllegalStateException]（配置错误应当显性）；
     * - 远端失败/超时/未实现 → 走 [onError]（主线程）；成功才回调接口声明的回调；
     * - 调用方**不会**在主线程被阻塞：实际 AIDL 调用在框架的 worker 线程执行。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> remoteApi(iface: Class<T>, target: String? = null, onError: ((String) -> Unit)? = null): T {
        val codec = synchronized(remoteApiCodecLock) { remoteApiCodecs[iface] }
            ?: throw IllegalStateException(
                "未注册 ${iface.name} 的远程 API 编解码器：请在调用方进程调用生成物" +
                    " TRouterRemoteApi_${iface.simpleName}.registerClient()（或 TRouter.registerRemoteApiClients(TRouterRemoteApiRegistry.all())）",
            )
        val handler = java.lang.reflect.InvocationHandler { proxy, method, rawArgs ->
            when (method.name) {
                "equals" -> proxy === rawArgs?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "TRouterRemoteApiProxy(${codec.apiName} → ${target ?: "default"})"
                else -> {
                    val last = rawArgs?.lastOrNull()
                    if (last !is Function1<*, *>) {
                        throw IllegalStateException(
                            "${iface.simpleName}.${method.name} 的最后一个参数必须是 (结果) -> Unit 回调（类型化接口约定）",
                        )
                    }
                    val methodCodec = codec.methods[method.name]
                        ?: throw IllegalStateException("生成物缺少方法编解码：${codec.apiName}#${method.name}")
                    dispatchTypedCall(
                        codec = codec,
                        methodCodec = methodCodec,
                        methodName = method.name,
                        values = rawArgs.dropLast(1),
                        target = target,
                        callback = last as (Any?) -> Unit,
                        onError = onError,
                    )
                    null
                }
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler) as T
    }

    /** 编码实参 → 经类型化通道发出 → 主线程解包并回调（失败走 onError）。 */
    private fun dispatchTypedCall(
        codec: TRouterRemoteApiCodec,
        methodCodec: TRouterRemoteMethodCodec,
        methodName: String,
        values: List<Any?>,
        target: String?,
        callback: (Any?) -> Unit,
        onError: ((String) -> Unit)?,
    ) {
        val traceId = UUID.randomUUID().toString().replace("-", "").take(8)

        fun fail(reason: String) {
            log("[remote][typed][error] traceId=$traceId service=${codec.apiName} method=$methodName reason=$reason")
            runOnMain { onError?.invoke(reason) }
        }

        val ctx = appContext
        if (ctx == null) {
            fail("remote 通道未初始化（TRouter.init 未完成）")
            return
        }
        val component = resolveRemoteComponent(target)
        if (target != null && component == null) {
            fail(remoteTargetMissingReason(target))
            return
        }

        val args = Bundle()
        try {
            methodCodec.encode(args, values)
        } catch (t: Throwable) {
            fail("参数编码失败：${t.javaClass.simpleName}: ${t.message}")
            return
        }

        remoteRouterFor(component, target).callTyped(
            context = ctx,
            component = component,
            service = codec.apiName,
            method = methodName,
            args = args,
            onResult = { reply ->
                runOnMain {
                    if (reply == null || !TRouterTypedReply.ok(reply)) {
                        fail(TRouterTypedReply.errorOf(reply))
                        return@runOnMain
                    }
                    val value = try {
                        methodCodec.decode(reply)
                    } catch (t: Throwable) {
                        fail("结果解码失败：${t.javaClass.simpleName}: ${t.message}")
                        return@runOnMain
                    }
                    log("[remote][typed][done] traceId=$traceId service=${codec.apiName} method=$methodName")
                    callback.invoke(value)
                }
            },
            logMessage = { log(it) },
        )
    }

    // ------------------------------------------------------------------ 测试支持

    /**
     * 已注册路由的只读快照（可观测性辅助：demo 路由目录/工具展示）。
     * 返回副本；未初始化时为空列表。
     */
    fun registeredRoutes(): List<RouteMeta> {
        if (!initialized) return emptyList()
        return routeTable.snapshot()
    }

    /** 测试底座专用：清空路由表与状态（core internal，同模块 debug 源码集可见）。 */
    internal fun resetForTest() {
        synchronized(remoteRoutersLock) {
            remoteRouters.values.forEach { it.disconnect() }
            remoteRouters.clear()
        }
        synchronized(liveInterceptorsLock) { liveInterceptors.clear() }
        synchronized(targetBindingsLock) { targetBindings.clear() }
        synchronized(aliasLock) { aliasTable.clear() }
        synchronized(servicesLock) { servicesTable.clear() }
        synchronized(endpointLock) { endpoints.clear() }
        synchronized(remoteApiLock) { remoteApiDispatchers.clear() }
        synchronized(remoteApiCodecLock) { remoteApiCodecs.clear() }
        lifecycleApp?.unregisterActivityLifecycleCallbacks(activityListener)
        lifecycleApp = null
        activityListener = null
        resumedActivity = null
        routeTable.clear()
        classCache.clear()
        initialized = false
        appContext = null
        config = TRouterConfig()
    }

    // ------------------------------------------------------------------ 内部实现

    /**
     * 只造 Intent、**不启动**（含路由元数据）：[openTarget] 与 [buildIntent] 共用的末端实现。
     *
     * 抽出来的原因：现代 Activity Result API（`registerForActivityResult`）要求调用方自己拿到 Intent 去 launch，
     * 而原实现把"造 Intent"和"启动页面"写死在一起，外部拿不到那个 Intent（见 [buildIntent]）。
     */
    private fun buildTargetIntent(meta: RouteMeta, bundle: Bundle?, traceId: String, startMs: Long): Intent {
        val ctx = requireNotNull(appContext) { "TRouter.init(context, config) 必须先行调用" }
        val intent = when (meta.kind) {
            RouteTargetKind.ACTIVITY ->
                Intent().setClassName(ctx, meta.targetClassName)
            RouteTargetKind.FRAGMENT ->
                FragmentContainerActivity.intent(ctx, meta.targetClassName)
        }
        // 顺序（审核修复）：先并入调用方 bundle，再写路由元数据 —— 保留键(RouteLaunch.*)永远不被用户参数覆盖；
        // FRAGMENT 目标的容器内部键(EXTRA_FRAGMENT_CLASS)在用户 bundle 之后再次断言，防冒充。
        if (bundle != null) intent.putExtras(bundle)
        intent
            .putExtra(RouteLaunch.EXTRA_PATH, meta.path)
            .putExtra(RouteLaunch.EXTRA_GROUP, meta.group)
            .putExtra(RouteLaunch.EXTRA_KIND, meta.kind.name)
            .putExtra(RouteLaunch.EXTRA_TRACE_ID, traceId)
            .putExtra(RouteLaunch.EXTRA_COST_MS, SystemClock.elapsedRealtime() - startMs)
        if (meta.kind == RouteTargetKind.FRAGMENT) {
            intent.putExtra(FragmentContainerActivity.EXTRA_FRAGMENT_CLASS, meta.targetClassName)
        }
        return intent
    }

    /**
     * 链末端：造 Intent 后按模式收口 ——
     * - [captureIntent] 非空：**只交给调用方**（[buildIntent] 路径），不启动任何页面；
     * - 否则照旧启动（`startActivityForResult` / `startActivity` / 无前台时 NEW_TASK 兜底）。
     */
    private fun openTarget(
        meta: RouteMeta,
        bundle: Bundle?,
        traceId: String,
        requestCode: Int? = null,
        captureIntent: ((Intent) -> Unit)? = null,
    ): RouteMeta {
        val ctx = requireNotNull(appContext) { "TRouter.init(context, config) 必须先行调用" }
        // 页面类在此刻才真正加载（惰性：init/install 不加载页面类）
        val startMs = SystemClock.elapsedRealtime()
        loadTargetClass(meta.targetClassName)

        val intent = buildTargetIntent(meta, bundle, traceId, startMs)
        if (captureIntent != null) {
            // 只产出、不启动：用哪个 launcher、什么时候 launch，由调用方决定
            captureIntent(intent)
            return meta
        }

        // 优先在当前（前台）Activity 的任务内打开 → 返回键/返回栈语义正确；
        // 无前台 UI（如通知/无界面场景）时才回退 applicationContext + NEW_TASK。
        val current = currentActivity()
        if (current != null && !current.isFinishing) {
            if (requestCode != null) {
                current.startActivityForResult(intent, requestCode)
            } else {
                current.startActivity(intent)
            }
        } else if (requestCode != null) {
            throw IllegalStateException("navigateForResult 需要前台 Activity（当前无 resumed Activity）")
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }
        return meta
    }

    private fun log(message: String) {
        if (!config.isDebug) return
        val sink = config.logSink
        if (sink != null) sink(message) else Timber.tag(LOG_TAG).d(message)
    }
}
