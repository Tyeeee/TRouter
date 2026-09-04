package com.trouter.core.api

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import com.trouter.core.internal.FragmentContainerActivity
import com.trouter.core.internal.RemoteRouter
import com.trouter.core.internal.RouteTable
import java.lang.ref.WeakReference
import java.util.UUID
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
 * 可观测性（V1.0 四个埋点，Timber，Tag=TRouter，消息为结构化 [节点] 行）：
 * navigate 入口/出口、GroupLoader 加载开始/结束；受 config.isDebug 控制。
 * logSink（测试收集器）与 logcat（Timber）收到的消息一致，无重复前缀。
 */
object TRouter {

    /** Timber 日志 Tag（logcat 过滤：adb logcat -s TRouter）。 */
    private const val LOG_TAG = "TRouter"

    /** 拦截器 Redirect 重定向累计跳数上限（防死循环，见 navigateInternal）。 */
    private const val MAX_REDIRECTS = 3

    @Volatile
    private var initialized = false
    private var appContext: Context? = null
    private var config: TRouterConfig = TRouterConfig()
    private val routeTable = RouteTable()

    // 生命周期绑定：追踪当前 resumed Activity，用于同任务内导航（返回键可回到调用页）
    private var lifecycleApp: Application? = null
    private var activityListener: Application.ActivityLifecycleCallbacks? = null
    private var resumedActivity: WeakReference<Activity>? = null
    private var timberPlanted = false

    // V4.0：host 侧跨进程通道客户端（按需 bind，reset 时断开）
    private val remoteRouter = RemoteRouter()

    // ------------------------------------------------------------------ 生命周期

    /**
     * 统一初始化入口。可重复调用（后调用覆盖前一次），
     * 但路由表不会自动清空 —— 测试环境请先调用 resetForTest()。
     */
    fun init(context: Context, config: TRouterConfig) {
        val app = context.applicationContext
        this.appContext = app
        this.config = config
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
                    // V3.0 可观测防线：重复 path（含跨模块/重复 install）如实告警，first-wins 语义固化
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
     * V2.0：命中路由后先走 config.interceptors 拦截链（解析后、打开前）；
     * 决策为 Redirect 时以同 traceId 重入（跳数上限 MAX_REDIRECTS，防死循环）。
     */
    fun navigate(path: String, bundle: Bundle? = null): TRouterResult {
        if (!initialized) return TRouterResult.NotInitialized
        val traceId = UUID.randomUUID().toString().replace("-", "").take(8)
        return navigateInternal(path, bundle, traceId, redirectHop = 0)
    }

    /**
     * 单跳导航实现（Redirect 重入复用本方法，同一 traceId 贯穿各跳）。
     * 每跳独立记录 navigate 入口/出口日志，hop 标注跳数（0 = 用户首次导航）。
     */
    private fun navigateInternal(path: String, bundle: Bundle?, traceId: String, redirectHop: Int): TRouterResult {
        val startMs = SystemClock.elapsedRealtime()

        // 埋点：navigate 入口（每跳一条）
        log("[navigate][entry] traceId=$traceId hop=$redirectHop path=$path bundleKeys=${bundle?.size() ?: 0}")

        // 未注册路径：丢失语义（V1.0），不经过拦截器
        val meta = routeTable.find(path)
        if (meta == null) {
            val cost = SystemClock.elapsedRealtime() - startMs
            // 埋点：navigate 出口（未找到）
            log("[navigate][exit] traceId=$traceId hop=$redirectHop result=NotFound(path=$path) costMs=$cost")
            // 降级回调（不受 isDebug 影响）
            config.onLost?.invoke(path)
            return TRouterResult.NotFound(path)
        }

        // V2.0 拦截链：解析后、打开前；空列表 = 直接放行（零日志，行为与 V1.0 一致）
        when (val decision = runInterceptors(meta, bundle, traceId)) {
            is InterceptorDecision.Block -> {
                val cost = SystemClock.elapsedRealtime() - startMs
                log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Blocked(path=${meta.path}, reason=${decision.reason}) costMs=$cost")
                return TRouterResult.Blocked(meta.path, decision.reason)
            }
            is InterceptorDecision.Redirect -> {
                if (redirectHop + 1 > MAX_REDIRECTS) {
                    val cost = SystemClock.elapsedRealtime() - startMs
                    val reason = "redirect loop（>=${MAX_REDIRECTS + 1} 跳）: ${decision.targetPath}"
                    log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Blocked(path=${meta.path}, reason=$reason) costMs=$cost")
                    return TRouterResult.Blocked(meta.path, reason)
                }
                // 本跳以重定向收尾：出口日志记 Redirect，再由目标 path 发起新跳（同 traceId）
                val cost = SystemClock.elapsedRealtime() - startMs
                log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Redirect(target=${decision.targetPath}) costMs=$cost")
                return navigateInternal(decision.targetPath, bundle, traceId, redirectHop + 1)
            }
            InterceptorDecision.Continue -> {
                // 全部放行 → 打开目标
            }
        }

        val opened = try {
            openTarget(meta, bundle, traceId)
        } catch (e: Throwable) {
            val cost = SystemClock.elapsedRealtime() - startMs
            log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Error(${e.javaClass.simpleName}: ${e.message}) costMs=$cost")
            config.onLost?.invoke(path)
            return TRouterResult.NotFound(path)
        }

        val cost = SystemClock.elapsedRealtime() - startMs
        // 埋点：navigate 出口（成功）
        log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Success(meta=${meta.path}, kind=${meta.kind}) costMs=$cost")
        return TRouterResult.Success(opened)
    }

    /** 拦截链执行：按 config.interceptors 顺序求值，任一非 Continue 短路；空列表零日志。 */
    private fun runInterceptors(meta: RouteMeta, bundle: Bundle?, traceId: String): InterceptorDecision {
        val interceptors = config.interceptors
        if (interceptors.isEmpty()) return InterceptorDecision.Continue

        val startMs = SystemClock.elapsedRealtime()
        // 埋点：拦截器开始
        log("[interceptor][start] traceId=$traceId path=${meta.path} interceptors=${interceptors.size}")

        var finalDecision: InterceptorDecision = InterceptorDecision.Continue
        try {
            for ((index, interceptor) in interceptors.withIndex()) {
                val decision = interceptor.intercept(meta, bundle)
                // 埋点：单个拦截器求值（顺序/短路可据此断言）
                log("[interceptor][eval] traceId=$traceId index=${index + 1} class=${interceptor.javaClass.simpleName} decision=${describe(decision)}")
                if (decision !== InterceptorDecision.Continue) {
                    finalDecision = decision
                    break
                }
            }
        } catch (e: Throwable) {
            // 拦截器自身故障 = 视为拦截：不打开目标、不触发 onLost、不崩溃
            log("[interceptor][eval] traceId=$traceId error=${e.javaClass.simpleName}: ${e.message}")
            finalDecision = InterceptorDecision.Block("interceptor error: ${e.javaClass.simpleName}: ${e.message}")
        }

        val cost = SystemClock.elapsedRealtime() - startMs
        // 埋点：拦截器结束
        log("[interceptor][end] traceId=$traceId decision=${describe(finalDecision)} costMs=$cost")
        return finalDecision
    }

    private fun describe(decision: InterceptorDecision): String = when (decision) {
        InterceptorDecision.Continue -> "Continue"
        is InterceptorDecision.Block -> "Block(reason=${decision.reason})"
        is InterceptorDecision.Redirect -> "Redirect(target=${decision.targetPath})"
    }

    // ------------------------------------------------------------------ 动态路由与图谱（V5.0）

    /**
     * 运行时注册路由（V5.0）：不依赖 KSP 生成物，与静态路由**同表共存**。
     * 目标仍只存类名字符串（惰性），拦截器/降级/快照/图谱自动生效。
     *
     * @return true 注册成功；false = 未初始化 或 与既有 path 冲突（拒绝覆盖，先 unregister 再注册）
     */
    fun registerRoute(meta: RouteMeta): Boolean {
        if (!initialized) return false
        val registered = routeTable.register(meta)
        if (registered) {
            log("[route][register] path=${meta.path} group=${meta.group} kind=${meta.kind} target=${meta.targetClassName}")
        } else {
            val keep = routeTable.find(meta.path)
            log("[route][register][conflict] path=${meta.path} keep=${keep?.targetClassName} incoming=${meta.targetClassName}")
        }
        return registered
    }

    /**
     * 运行时注销路由（V5.0）：静态与动态路由均可注销（路径级统一语义）。
     * @return true 表示确有移除；未初始化或不存在返回 false。
     */
    fun unregisterRoute(path: String): Boolean {
        if (!initialized) return false
        val removed = routeTable.remove(path)
        if (removed) log("[route][unregister] path=$path")
        return removed
    }

    /**
     * 路由图谱（V5.0）：由当前路由表（静态 + 动态）实时导出，顺序确定（按注册/插入序）。
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

    // ------------------------------------------------------------------ 跨进程导航（V4.0）

    /**
     * 跨进程导航：把导航请求经 AIDL 发给 remote 进程，由**远端进程自己的 TRouter** 解析并打开，
     * 结果摘要回传后重建为统一 [TRouterResult]，在主线程回调 [onResult]。
     *
     * @param path 必须来自 RouterContract 常量，且（配置了 remoteWhitelist 时）已标注 @CrossProcess
     * @param onResult 远端结果回调（主线程）；守卫/超时失败以 Blocked 表达，不触发 onLost
     *
     * 要求：config.remoteService 已配置且远端进程 Application 已 init/install（各进程独立路由表）。
     */
    fun navigateRemote(path: String, bundle: Bundle? = null, onResult: (TRouterResult) -> Unit) {
        if (!initialized) {
            onResult(TRouterResult.NotInitialized)
            return
        }
        val ctx = appContext
        if (ctx == null) {
            onResult(TRouterResult.Blocked(path, "remote 通道未初始化（TRouter.init 未完成）"))
            return
        }
        remoteRouter.navigate(ctx, config.remoteService, config.remoteWhitelist, path, bundle, onResult) {
            log(it)
        }
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
        remoteRouter.disconnect()
        lifecycleApp?.unregisterActivityLifecycleCallbacks(activityListener)
        lifecycleApp = null
        activityListener = null
        resumedActivity = null
        routeTable.clear()
        initialized = false
        appContext = null
        config = TRouterConfig()
    }

    // ------------------------------------------------------------------ 内部实现

    private fun openTarget(meta: RouteMeta, bundle: Bundle?, traceId: String): RouteMeta {
        val ctx = requireNotNull(appContext) { "TRouter.init(context, config) 必须先行调用" }
        // 页面类在此刻才真正加载（惰性：init/install 不加载页面类）
        val startMs = SystemClock.elapsedRealtime()
        Class.forName(meta.targetClassName)

        val intent = when (meta.kind) {
            RouteTargetKind.ACTIVITY ->
                android.content.Intent().setClassName(ctx, meta.targetClassName)
            RouteTargetKind.FRAGMENT ->
                FragmentContainerActivity.intent(ctx, meta.targetClassName)
        }
        // 携带路由元数据：目标页据此渲染「由 TRouter 成功打开」的运行时证据
        intent
            .putExtra(RouteLaunch.EXTRA_PATH, meta.path)
            .putExtra(RouteLaunch.EXTRA_GROUP, meta.group)
            .putExtra(RouteLaunch.EXTRA_KIND, meta.kind.name)
            .putExtra(RouteLaunch.EXTRA_TRACE_ID, traceId)
            .putExtra(RouteLaunch.EXTRA_COST_MS, SystemClock.elapsedRealtime() - startMs)
        if (bundle != null) intent.putExtras(bundle)

        // 优先在当前（前台）Activity 的任务内打开 → 返回键/返回栈语义正确；
        // 无前台 UI（如通知/无界面场景）时才回退 applicationContext + NEW_TASK。
        val current = currentActivity()
        if (current != null && !current.isFinishing) {
            current.startActivity(intent)
        } else {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
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
