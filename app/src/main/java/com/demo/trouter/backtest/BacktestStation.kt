package com.demo.trouter.backtest

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.demo.trouter.DemoInterceptors
import com.demo.trouter.DemoProcessWiring
import com.demo.trouter.DemoRouteRegistry
import com.demo.trouter.DemoRouterConfig
import com.trouter.core.api.TRouter
import java.io.File

/**
 * 回测台（TRouter Backtest Station）：把"这个库到底能不能用"变成一次**可重复、可留档**的真实操作。
 *
 * 与普通单元测试的区别（这是本回测台的立足点）：
 * 1. 节点执行的是**真实操作**——真的 navigate、真的把页面切到前台、真的点返回、真的跨进程调用；
 * 2. 校验的是**可见效果**——屏幕上真的出现了哪个 Activity、它真实拿到的 intent/Fragment 参数是什么、
 *    返回后宿主真的收到了结果，而不是 navigate() 返回了什么；
 * 3. 每个节点都会记录**证据行**，报告可以脱离执行者被复核；
 * 4. 台子本身是一个可点的页面（`/backtest`），人可以一键跑，机器也可以驱动它跑。
 */
object BacktestStation {

    private const val TAG = "TRouterBacktest"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最近一次回测报告（人机两个入口都读它）。 */
    @Volatile
    var lastReport: BacktestReport? = null
        private set

    @Volatile
    var running: Boolean = false
        private set

    /** onLost 兜底回调的收集口（由 DemoRouterConfig 注入）。 */
    private val lostPaths = ArrayList<String>()

    /** 全部节点（按功能点分组顺序）。 */
    fun allNodes(): List<BacktestNode> =
        BacktestNodesCore.nodes +
            BacktestNodesChain.nodes +
            BacktestNodesDynamic.nodes +
            BacktestNodesRemote.nodes

    /** 按功能点分组，供界面与报告展示。 */
    fun nodesByFeature(): LinkedHashMap<String, List<BacktestNode>> {
        val map = LinkedHashMap<String, List<BacktestNode>>()
        for (node in allNodes()) {
            map[node.feature] = (map[node.feature] ?: emptyList()) + node
        }
        return map
    }

    /** 在后台线程跑一轮（界面入口；结果经 [onDone] 回主线程）。 */
    fun run(host: BacktestHost, only: Collection<String>? = null, onDone: ((BacktestReport) -> Unit)? = null) {
        if (running) {
            host.onLog("已有回测在进行中，本次请求忽略")
            return
        }
        running = true
        Thread({
            val report = try {
                execute(host, only)
            } catch (t: Throwable) {
                falseReport(host, t)
            }
            lastReport = report
            running = false
            mainHandler.post { onDone?.invoke(report) }
        }, "TRouter-Backtest").start()
    }

    /** 同步跑一轮（自动化入口：设备内用例直接调用它，不依赖界面）。 */
    fun runBlocking(host: BacktestHost, only: Collection<String>? = null): BacktestReport {
        running = true
        val report = try {
            execute(host, only)
        } catch (t: Throwable) {
            falseReport(host, t)
        }
        lastReport = report
        running = false
        return report
    }

    // ------------------------------------------------------------------ 执行

    private fun execute(host: BacktestHost, only: Collection<String>?): BacktestReport {
        val app = host.hostActivity.application as Application
        val watcher = ActivityWatcher(app)
        watcher.start()
        val startedAt = System.currentTimeMillis()
        val results = ArrayList<NodeResult>()
        try {
            prepare(host, app)
            val nodes = allNodes().filter { only == null || it.id in only }
            host.onLog("回测开始：共 ${nodes.size} 个节点")
            nodes.forEachIndexed { index, node ->
                host.onNodeStart(node, index, nodes.size)
                val result = runNode(node, host, watcher)
                results += result
                host.onNodeFinished(result)
                host.onLog("${result.status.label} ${node.id} ${node.title} — ${result.detail}")
                // 逐条即时输出：一轮回测要跑一两分钟，中途被打断（设备被杀/断电）也要留下已完成节点的结论
                android.util.Log.i(
                    TAG,
                    "BT|NODE|id=${result.id}|status=${result.status.name}|feature=${result.feature}|" +
                        "costMs=${result.costMs}|title=${result.title}|detail=${result.detail.replace('\n', ' ')}",
                )
            }
        } catch (t: Throwable) {
            // 台子自身出问题（prep 阶段等）：也要留档，而不是静默失败
            results += NodeResult(
                id = "STATION",
                feature = "台子自身",
                title = "回测台准备阶段",
                expected = "回测台能正常准备并开始跑节点",
                status = NodeStatus.FAIL,
                detail = "回测台异常：${t.javaClass.simpleName}: ${t.message}",
                evidence = emptyList(),
                costMs = 0,
            )
        } finally {
            watcher.stop()
        }
        val report = BacktestReport(
            startedAtMs = startedAt,
            finishedAtMs = System.currentTimeMillis(),
            results = results,
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / API ${android.os.Build.VERSION.SDK_INT}",
            hostProcess = com.demo.trouter.DemoProcess.name(app),
            hostPid = Process.myPid(),
        )
        persist(app, report)
        return report
    }

    /**
     * 确保 TRouter 处于"与真实 App 一致"的装配状态（幂等，可重复调用）。
     *
     * 谁需要它：
     * - 回测台每次开跑之前；
     * - 回测台页面自己 onCreate 时——因为同一个进程里可能刚跑过会 `resetForTest()` 的用例，
     *   那时 TRouter 是"未初始化"的（表现就是点按钮没反应）。页面自己把状态摆正，
     *   人点和机器点就都靠得住了。
     */
    fun ensureWiring(app: Application, onLog: ((String) -> Unit)? = null) {
        mainValue {
            TRouter.init(app, DemoRouterConfig.create(app, onLost = { lostPaths.add(it) }))
            TRouter.install(DemoRouteRegistry)
            // 关键：端点表/类型化接口/目标拦截器绑定会被 reset 清空，
            // 这里用与 Application 完全同一份装配函数补回来，避免"回测环境是空壳"导致的假失败
            DemoProcessWiring.apply(app)
        }
        onLog?.invoke("装配完成：isDebug=true、deep link scheme=trouter、拦截器链=${TRouter.registeredInterceptors().size} 个")
    }

    private fun prepare(host: BacktestHost, app: Application) {
        ensureWiring(app) { host.onLog(it) }
        resetInterceptorSwitches()
        RouteLog.clear()
    }

    private fun runNode(node: BacktestNode, host: BacktestHost, watcher: ActivityWatcher): NodeResult {
        val ctx = BacktestContext(host, watcher, lostPaths)
        val startMs = SystemClock.elapsedRealtime()
        val mark = watcher.mark()
        var status = NodeStatus.PASS
        var detail = "按预期完成"
        try {
            resetInterceptorSwitches()
            // 每个节点都从同一个前置条件开始：回测台在前台、拦截器开关是默认值。
            // 少了这一步，上一个节点留下的"远端页面"会把回测台压到后台，
            // 后面节点的失败就会变成台子没复位导致的假象。
            ctx.restoreHostForeground()
            node.body(ctx)
        } catch (e: NodeFailure) {
            status = NodeStatus.FAIL
            detail = e.message ?: "断言失败"
        } catch (t: Throwable) {
            status = NodeStatus.FAIL
            detail = "节点抛异常：${t.javaClass.simpleName}: ${t.message}"
        } finally {
            ctx.runCleanups()
            ctx.closeAllPagesSince(mark)
            resetInterceptorSwitches()
            awaitHostQuiet(watcher)
        }
        // 诊断行：万一下一个节点"站在错误的前台上"跑，能一眼看出是谁留下的
        android.util.Log.i(
            TAG,
            "BT|DIAG|id=${node.id}|status=${status.name}|hostResumed=${host.hostResumed}|" +
                "前台=${watcher.current()?.javaClass?.simpleName}|本节点打开=${watcher.since(mark).map { it.activity.javaClass.simpleName }}",
        )
        return NodeResult(
            id = node.id,
            feature = node.feature,
            title = node.title,
            expected = node.expected,
            status = status,
            detail = detail,
            evidence = ctx.evidence.toList(),
            costMs = SystemClock.elapsedRealtime() - startMs,
        )
    }

    /** 每个节点开始/结束时都把演示拦截器恢复成"默认全关"，避免节点之间互相污染。 */
    private fun resetInterceptorSwitches() {
        DemoInterceptors.gate.enabled = false
        DemoInterceptors.mock.enabled = false
        DemoInterceptors.async.enabled = false
        DemoInterceptors.async.delayMs = 300L
    }

    private fun awaitHostQuiet(watcher: ActivityWatcher) {
        watcher.awaitAnyResumed(watcher.mark(), 200)
        Thread.sleep(120)
    }

    private fun falseReport(host: BacktestHost, t: Throwable): BacktestReport {
        val now = System.currentTimeMillis()
        return BacktestReport(
            startedAtMs = now,
            finishedAtMs = now,
            results = listOf(
                NodeResult(
                    id = "STATION",
                    feature = "台子自身",
                    title = "回测执行",
                    expected = "整轮回测能跑完",
                    status = NodeStatus.FAIL,
                    detail = "回测执行异常：${t.javaClass.simpleName}: ${t.message}",
                    evidence = emptyList(),
                    costMs = 0,
                ),
            ),
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / API ${android.os.Build.VERSION.SDK_INT}",
            hostProcess = com.demo.trouter.DemoProcess.name(host.hostActivity),
            hostPid = Process.myPid(),
        )
    }

    private fun <T> mainValue(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var value: Any? = null
        var error: Throwable? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            try {
                value = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(20, java.util.concurrent.TimeUnit.SECONDS)) error = NodeFailure("主线程等待超时")
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /**
     * 报告落盘 + 输出结论行。
     *
     * 逐节点的结论行已经在每个节点结束时即时输出了（[execute]），这里只补结论汇总与人类可读的报告文件
     * ——这样即使一轮回测中途被打断（设备被杀/断电），已经跑完的节点也不会白跑。
     */
    private fun persist(app: Context, report: BacktestReport) {
        android.util.Log.i(TAG, report.summaryLogLine())
        runCatching {
            val dir = File(app.filesDir, "backtest")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "last-report.txt").writeText(report.toText())
        }
    }
}
