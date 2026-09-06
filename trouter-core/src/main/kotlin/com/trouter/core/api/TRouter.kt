package com.trouter.core.api

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
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

    /** G5：别名注册表中正则别名前缀（exact 优先，其次按注册序正则匹配）。 */
    private const val ALIAS_REGEX_PREFIX = "regex:"

    @Volatile
    private var initialized = false
    private var appContext: Context? = null
    private var config: TRouterConfig = TRouterConfig()
    private val routeTable = RouteTable()

    // G8：目标 Class 按进程缓存（首次 navigate 时加载一次，后续命中缓存）
    private val classCache = java.util.concurrent.ConcurrentHashMap<String, Class<*>>()

    // L2：运行时拦截器注册表。读写均在锁内做「快照或原子替换」，navigate 每次取不可变快照：
    // 增删绝不打断进行中的链，只影响下一次 navigate（吸取「顺序/时机依赖」缺陷教训）。
    private val liveInterceptorsLock = Any()
    private val liveInterceptors = ArrayList<RouteChainMember>()

    // L3：目标级拦截器绑定表（name -> member）。CopyOnWrite 快照语义与 liveInterceptors 一致。
    private val targetBindingsLock = Any()
    private val targetBindings = LinkedHashMap<String, RouteChainMember>()

    // G5：路由别名表（alias -> 真实 path；支持 regex: 前缀正则别名）
    private val aliasLock = Any()
    private val aliasTable = LinkedHashMap<String, String>()

    // G2：进程内服务注册表（接口 Class -> 实现实例）
    private val servicesLock = Any()
    private val servicesTable = LinkedHashMap<Class<*>, Any>()

    // G2-remote：跨进程服务端点注册表（名称 -> (Bundle)->String，各进程独立注册）
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
        return navigateInternal(path, bundle, traceId, redirectHop = 0, requestCode = null)
    }

    /**
     * 带结果回调的导航（G3）：以 startActivityForResult 发起，结果由调用方 Activity 的
     * onActivityResult/ResultLauncher 接收（与系统语义一致）；无前台 Activity 时返回 Blocked。
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
     * 单跳导航实现（Redirect 重入复用本方法，同一 traceId 贯穿各跳）。
     * 每跳独立记录 navigate 入口/出口日志，hop 标注跳数（0 = 用户首次导航）。
     */
    private fun navigateInternal(
        path: String,
        bundle: Bundle?,
        traceId: String,
        redirectHop: Int,
        requestCode: Int? = null,
    ): TRouterResult {
        val startMs = SystemClock.elapsedRealtime()

        // 埋点：navigate 入口（每跳一条）
        log("[navigate][entry] traceId=$traceId hop=$redirectHop path=$path bundleKeys=${bundle?.size() ?: 0}")

        // 未注册路径：先查 G5 别名（精确 -> 正则），命中则转向真实 path（记跳数防环）
        var meta = routeTable.find(path)
        if (meta == null) {
            val resolved = resolveAlias(path)
            if (resolved != null) {
                if (redirectHop + 1 > MAX_REDIRECTS) {
                    val cost = SystemClock.elapsedRealtime() - startMs
                    val reason = "alias loop（>=$MAX_REDIRECTS 跳）: $path"
                    log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Blocked(path=$path, reason=$reason) costMs=$cost")
                    return TRouterResult.Blocked(path, reason)
                }
                log("[route][alias] from=$path to=$resolved hop=$redirectHop")
                return navigateInternal(resolved, bundle, traceId, redirectHop + 1, requestCode)
            }
            val cost = SystemClock.elapsedRealtime() - startMs
            // 埋点：navigate 出口（未找到）
            log("[navigate][exit] traceId=$traceId hop=$redirectHop result=NotFound(path=$path) costMs=$cost")
            // 降级回调（不受 isDebug 影响）
            config.onLost?.invoke(path)
            return TRouterResult.NotFound(path)
        }

        // L4 拦截链：原子拦截器（RouteInterceptor）+ 洋葱包裹拦截器（WrappingInterceptor）统一执行，
        // 链末端（或 wrapper 调 proceed()）打开目标。空列表 = 直接打开（零日志，行为与 V1.0 一致）。
        // 打开/包装期间抛出的异常在链外统一按「打开失败」处理（Error 出口 + onLost），与 V1 语义一致。
        val outcome = try {
            runChain(meta, bundle, traceId, requestCode)
        } catch (e: Throwable) {
            val cost = SystemClock.elapsedRealtime() - startMs
            log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Error(${e.javaClass.simpleName}: ${e.message}) costMs=$cost")
            config.onLost?.invoke(path)
            return TRouterResult.NotFound(path)
        }

        return when (outcome) {
            is ChainOutcome.Opened -> {
                val cost = SystemClock.elapsedRealtime() - startMs
                log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Success(meta=${outcome.meta.path}, kind=${outcome.meta.kind}) costMs=$cost")
                TRouterResult.Success(outcome.meta)
            }
            is ChainOutcome.Blocked -> {
                val cost = SystemClock.elapsedRealtime() - startMs
                log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Blocked(path=${outcome.path}, reason=${outcome.reason}) costMs=$cost")
                TRouterResult.Blocked(outcome.path, outcome.reason)
            }
            is ChainOutcome.Redirected -> {
                if (redirectHop + 1 > MAX_REDIRECTS) {
                    val cost = SystemClock.elapsedRealtime() - startMs
                    val reason = "redirect loop（>=${MAX_REDIRECTS + 1} 跳）: ${outcome.targetPath}"
                    log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Blocked(path=${meta.path}, reason=$reason) costMs=$cost")
                    TRouterResult.Blocked(meta.path, reason)
                } else {
                    // 本跳以重定向收尾：出口日志记 Redirect，再由目标 path 发起新跳（同 traceId）
                    val cost = SystemClock.elapsedRealtime() - startMs
                    log("[navigate][exit] traceId=$traceId hop=$redirectHop result=Redirect(target=${outcome.targetPath}) costMs=$cost")
                    return navigateInternal(outcome.targetPath, bundle, traceId, redirectHop + 1, requestCode)
                }
            }
        }
    }

    /**
     * L4 链执行入口：取 config.interceptors 快照后递归执行；链末端打开目标。
     * 单次 navigate 的执行是同步调用栈推进（无并发交错）；拦截器内部抛异常按
     * 「该拦截器故障 = Blocked」处理（不打开、不触发 onLost、不崩溃）。
     */
    private fun runChain(meta: RouteMeta, bundle: Bundle?, traceId: String, requestCode: Int? = null): ChainOutcome {
        val members = combinedMembers(meta, traceId)
            ?: return ChainOutcome.Blocked(meta.path, "目标拦截器未注册（@Interceptor names 需先 bindTargetInterceptor）")
        if (members.isEmpty()) return openTargetOutcome(meta, bundle, traceId, requestCode)

        val startMs = SystemClock.elapsedRealtime()
        log("[interceptor][start] traceId=$traceId path=${meta.path} interceptors=${members.size}")

        val outcome = stepChain(members, 0, meta, bundle, traceId, requestCode)

        val cost = SystemClock.elapsedRealtime() - startMs
        log("[interceptor][end] traceId=$traceId decision=${describeOutcome(outcome)} costMs=$cost")
        return outcome
    }

    /**
     * 组装一次导航的链快照 = 全局运行时拦截器(L2) + 目标级拦截器(L3，按 resolver 解析并按名取绑定)。
     * @return null 表示解析失败/存在未注册的目标拦截器名（调用方转为 Blocked）。
     */
    private fun combinedMembers(meta: RouteMeta, traceId: String): List<RouteChainMember>? {
        // G11：全局链按 priority 降序稳定排序（默认 0 = 保持声明顺序）
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

    /** 递归推进：index 到达成员末尾 = 打开目标；否则按成员类型执行（旧原子 or 洋葱包裹）。 */
    private fun stepChain(
        members: List<RouteChainMember>,
        index: Int,
        meta: RouteMeta,
        bundle: Bundle?,
        traceId: String,
        requestCode: Int? = null,
    ): ChainOutcome {
        if (index >= members.size) return openTargetOutcome(meta, bundle, traceId, requestCode)

        val member = members[index]
        return when (member) {
            is RouteInterceptor -> {
                val decision = try {
                    member.intercept(meta, bundle)
                } catch (e: Throwable) {
                    log("[interceptor][eval] traceId=$traceId error=${e.javaClass.simpleName}: ${e.message}")
                    return ChainOutcome.Blocked(meta.path, "interceptor error: ${e.javaClass.simpleName}: ${e.message}")
                }
                log("[interceptor][eval] traceId=$traceId index=${index + 1} class=${member.javaClass.simpleName} decision=${describeDecision(decision)}")
                when (decision) {
                    InterceptorDecision.Continue -> stepChain(members, index + 1, meta, bundle, traceId, requestCode)
                    is InterceptorDecision.Block -> ChainOutcome.Blocked(meta.path, decision.reason)
                    is InterceptorDecision.Redirect -> ChainOutcome.Redirected(decision.targetPath)
                }
            }
            is WrappingInterceptor -> {
                log("[interceptor][eval] traceId=$traceId index=${index + 1} class=${member.javaClass.simpleName} decision=Wrap")
                val nextIndex = index + 1
                val singleShotChain = object : InterceptorChain {
                    override val meta: RouteMeta = meta
                    override val bundle: Bundle? = bundle
                    private var consumed = false

                    override fun proceed(): ChainOutcome {
                        if (consumed) {
                            // 同一条链二次放行 = 重复打开目标的隐患，直接抛错（由外层按拦截器故障处理）
                            throw IllegalStateException("InterceptorChain.proceed 只能调用一次（防止重复打开目标）")
                        }
                        consumed = true
                        return stepChain(members, nextIndex, meta, bundle, traceId, requestCode)
                    }
                }
                try {
                    member.intercept(singleShotChain)
                } catch (e: Throwable) {
                    log("[interceptor][eval] traceId=$traceId error=${e.javaClass.simpleName}: ${e.message}")
                    ChainOutcome.Blocked(meta.path, "interceptor error: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            else -> ChainOutcome.Blocked(meta.path, "未知拦截器类型: ${member.javaClass.name}")
        }
    }

    /** 链末端：真正打开目标（异常向上冒泡，由 navigateInternal 按打开失败处理）。 */
    private fun openTargetOutcome(meta: RouteMeta, bundle: Bundle?, traceId: String, requestCode: Int? = null): ChainOutcome {
        val opened = openTarget(meta, bundle, traceId, requestCode)
        return ChainOutcome.Opened(opened)
    }

    /** G8：目标类加载（带进程内缓存）；失败抛 ClassNotFoundException（由调用方按打开失败处理）。 */
    @Suppress("UNCHECKED_CAST")
    private fun loadTargetClass(className: String): Class<*> =
        classCache.computeIfAbsent(className) { Class.forName(it) }

    /**
     * G7：路由目标合法性校验（可观测/测试/构建辅助）。
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

    // ------------------------------------------------------------------ 路由表 JSON（G6）与服务层（G2）

    /** G6：把当前全部路由（静态+动态）导出为规范 JSON（供下发/审计）。 */
    fun exportRouteMapJson(): String = RouteMapCodec.toJson(registeredRoutes())

    /**
     * G6：导入路由表 JSON 作为**动态覆盖层**：先清当前动态路由，再原子注册导入内容；
     * 导入与静态路由冲突或格式非法 → 整批失败、零变更。
     * @return true = 导入并覆盖成功。
     */
    fun importRouteMapJson(text: String): Boolean {
        if (!initialized) return false
        val metas = RouteMapCodec.fromJson(text) ?: return false
        val removes = exportDynamicRoutes().map { it.path }
        return applyRouteConfig(removes = removes, adds = metas)
    }

    /** G2：注册进程内服务（接口 key → 实现实例）。重复接口返回 false。 */
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

    /** G2：注销进程内服务。@return true = 确有移除。 */
    fun unregisterService(serviceClass: Class<*>): Boolean {
        if (!initialized) return false
        val removed = synchronized(servicesLock) { servicesTable.remove(serviceClass) }
        if (removed != null) log("[service][unregister] interface=${serviceClass.name}")
        return removed != null
    }

    /** G2：按接口查找服务实例；未注册返回 null（调用方自行判空，不抛异常）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> findService(serviceClass: Class<T>): T? =
        synchronized(servicesLock) { servicesTable[serviceClass] as T? }

    /** G2：已注册服务接口快照。 */
    fun registeredServices(): List<Class<*>> =
        synchronized(servicesLock) { ArrayList(servicesTable.keys) }

    /**
     * G2-remote：注册跨进程服务端点（各进程独立注册表；名称重复拒绝）。
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


    // ------------------------------------------------------------------ 拦截器运行时增删（L2）

    /**
     * 运行时追加拦截器（L2）。立即对**下一次** navigate 生效（本次进行中的链不受影响）。
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
     * 运行时移除拦截器（L2，按实例引用）。
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

    /** 已注册拦截器只读快照（L2，可观测）。 */
    fun registeredInterceptors(): List<RouteChainMember> {
        if (!initialized) return emptyList()
        return interceptorSnapshot()
    }

    /** 拦截器链使用的不可变快照（单线程锁内拷贝；navigate 各次互不干扰）。 */
    private fun interceptorSnapshot(): List<RouteChainMember> =
        synchronized(liveInterceptorsLock) { ArrayList(liveInterceptors) }

    // ------------------------------------------------------------------ 目标级拦截器绑定（L3）

    /**
     * 把标识名绑定到拦截器实例（L3，与 @Interceptor(names) 配套）。
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

    /** 解绑标识名（L3）。@return true = 确有解绑。 */
    fun unbindTargetInterceptor(name: String): Boolean {
        if (!initialized) return false
        val removed = synchronized(targetBindingsLock) { targetBindings.remove(name) }
        if (removed != null) log("[interceptor][target][unbind] name=$name")
        return removed != null
    }

    /** 已绑定目标拦截器只读快照（L3）。 */
    fun registeredTargetInterceptors(): Map<String, RouteChainMember> =
        synchronized(targetBindingsLock) { LinkedHashMap(targetBindings) }

    // ------------------------------------------------------------------ 深链（G1）与路由别名（G5）

    /**
     * URI/Scheme 深链入口（G1）：scheme 须在 config.deeplinkSchemes 白名单内；
     * uri.path 段即内部路由 path；query 并入导航参数（query 优先于入参 bundle）。
     */
    fun navigateUri(uri: Uri, bundle: Bundle? = null): TRouterResult {
        if (!initialized) return TRouterResult.NotInitialized
        val scheme = uri.scheme
        if (scheme.isNullOrEmpty() || scheme !in (config.deeplinkSchemes ?: emptySet())) {
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
     * 注册路由别名（G5）：alias 未注册时导航 alias 会转向 [toPath]。
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

    /** 注销路由别名（G5）。@return true = 确有移除。 */
    fun unregisterRouteAlias(alias: String): Boolean {
        if (!initialized) return false
        val removed = synchronized(aliasLock) { aliasTable.remove(alias) }
        if (removed != null) log("[route][alias][unregister] alias=$alias")
        return removed != null
    }

    /** 已注册别名只读快照（G5）。 */
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
            synchronized(dynamicPathsLock) { dynamicPaths.add(meta.path) }
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
        if (removed) {
            synchronized(dynamicPathsLock) { dynamicPaths.remove(path) }
            log("[route][unregister] path=$path")
        }
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

    /**
     * G2-remote：跨进程调用远端进程注册的服务端点（结果原样 String 回传主线程；
     * 失败以 RemoteReplyCodec.SERVICE_ERROR_PREFIX 前缀串表达）。
     */
    fun callRemoteService(name: String, args: Bundle? = null, onResult: (String) -> Unit) {
        if (!initialized) {
            onResult(REMOTE_ERR_PREFIX + "TRouter 未初始化")
            return
        }
        val ctx = appContext
        if (ctx == null) {
            onResult(REMOTE_ERR_PREFIX + "remote 通道未初始化（TRouter.init 未完成）")
            return
        }
        remoteRouter.callService(ctx, config.remoteService, name, args, onResult) { log(it) }
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
        synchronized(liveInterceptorsLock) { liveInterceptors.clear() }
        synchronized(targetBindingsLock) { targetBindings.clear() }
        synchronized(aliasLock) { aliasTable.clear() }
        synchronized(servicesLock) { servicesTable.clear() }
        synchronized(endpointLock) { endpoints.clear() }
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

    private fun openTarget(meta: RouteMeta, bundle: Bundle?, traceId: String, requestCode: Int? = null): RouteMeta {
        val ctx = requireNotNull(appContext) { "TRouter.init(context, config) 必须先行调用" }
        // 页面类在此刻才真正加载（惰性：init/install 不加载页面类）
        val startMs = SystemClock.elapsedRealtime()
        loadTargetClass(meta.targetClassName)

        val intent = when (meta.kind) {
            RouteTargetKind.ACTIVITY ->
                android.content.Intent().setClassName(ctx, meta.targetClassName)
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
