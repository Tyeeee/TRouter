package com.demo.trouter.backtest

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.trouter.core.api.RouteChainMember
import com.trouter.core.api.TRouter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 节点执行上下文：节点用它做**真实操作**（跳转、点击、等待回调）与**效果断言**。
 *
 * 线程模型（重要）：回测在**后台线程**上顺序跑节点，所有与 Android 生命周期相关的事
 * （navigate / startActivity / finish）都经 [mainValue] 切回主线程执行，
 * 需要等待的结果（异步回调、页面切前台）则在后台线程上阻塞等待——既不卡 UI，也不需要 sleep 猜时间。
 */
class BacktestContext internal constructor(
    val host: BacktestHost,
    private val watcher: ActivityWatcher,
    private val lostPaths: MutableList<String>,
) {

    /** 本节点收集到的证据行（写进报告）。 */
    val evidence = ArrayList<String>()

    private val cleanups = ArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundErrors = ArrayList<Throwable>()

    // ------------------------------------------------------------------ 记录与断言

    fun note(text: String) {
        evidence += text
    }

    fun require(condition: Boolean, message: String) {
        if (!condition) throw NodeFailure(message)
    }

    fun <T> expect(actual: T, expected: T, what: String) {
        if (actual != expected) {
            throw NodeFailure("$what 与预期不符：期望=$expected 实际=$actual")
        }
        evidence += "$what=$actual"
    }

    fun expectContains(actual: String, needle: String, what: String) {
        if (!actual.contains(needle)) {
            throw NodeFailure("$what 未包含「$needle」，实际=$actual")
        }
        evidence += "$what 含「$needle」"
    }

    fun expectNotContains(actual: String, needle: String, what: String) {
        if (actual.contains(needle)) {
            throw NodeFailure("$what 不该包含「$needle」，实际=$actual")
        }
        evidence += "$what 不含「$needle」"
    }

    /** 注册节点收尾动作（无论成败都会执行），用于恢复被节点改动的全局状态。 */
    fun onCleanup(block: () -> Unit) {
        cleanups += block
    }

    internal fun runCleanups() {
        for (i in cleanups.indices.reversed()) {
            runCatching { cleanups[i]() }
        }
        cleanups.clear()
    }

    // ------------------------------------------------------------------ 线程与等待

    fun <T> mainValue(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var value: Any? = null
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                value = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(20, TimeUnit.SECONDS)) throw NodeFailure("主线程执行超时（20s）")
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    fun main(block: () -> Unit) {
        mainValue { block() }
    }

    fun sleep(ms: Long) {
        Thread.sleep(ms)
    }

    /**
     * 等待一次异步回调（TRouter 的异步接口都在主线程回调）。
     * [register] 在主线程被调用，用来发起这次调用并把回调接到内部闩锁上。
     */
    fun <T> awaitCallback(timeoutMs: Long, what: String, register: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<Any>(1)
        mainValue { register { v -> holder[0] = v; latch.countDown() } }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw NodeFailure("等待「$what」超时（${timeoutMs}ms）${backgroundErrorSuffix()}")
        }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    /** 主线程是否真的没被阻塞（异步节点用它证明"同步导航没有等待异步拦截器"）。 */
    fun mainThreadResponsiveWithin(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        val posted = SystemClock.elapsedRealtime()
        mainHandler.post { latch.countDown() }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS).also {
            evidence += "主线程在 ${SystemClock.elapsedRealtime() - posted}ms 内响应（阈值 ${timeoutMs}ms）"
        }
    }

    // ------------------------------------------------------------------ 页面观测

    fun mark(): Long = watcher.mark()

    fun currentActivity(): Activity? = watcher.current()

    fun describePagesSince(mark: Long): String = watcher.describeSince(mark)

    /** 断言某个类**真的**被打开（并返回实例，供继续检查它的 intent/参数）。 */
    fun awaitPage(clazz: Class<*>, since: Long, timeoutMs: Long = 6_000): Activity {
        val activity = watcher.awaitResumed(clazz, since, timeoutMs)
            ?: throw NodeFailure(
                "期望页面 ${clazz.simpleName} 被打开，但 ${timeoutMs}ms 内实际观察到的是：${watcher.describeSince(since)}",
            )
        evidence += "真实打开的页面=${activity.javaClass.simpleName}"
        return activity
    }

    /** 等待这之后出现的第一个页面（用于"打开的是不是它"之外的场景）。 */
    fun awaitAnyPage(since: Long, timeoutMs: Long = 6_000): Activity? =
        watcher.awaitAnyResumed(since, timeoutMs)

    /** 反向断言：这段时间内**不该**有任何新页面被打开（拦截/未找到场景）。 */
    fun expectNoPageOpened(since: Long, quietMs: Long = 900) {
        val opened = watcher.awaitAnyResumed(since, quietMs)
        if (opened != null) {
            throw NodeFailure("期望没有任何页面被打开，但实际打开了 ${opened.javaClass.simpleName}")
        }
        evidence += "${quietMs}ms 内没有任何页面被打开"
    }

    /** 关掉一个页面的方式与真实用户点"返回"一致：调用 Activity.finish()。 */
    fun finishPage(activity: Activity) {
        main { if (!activity.isFinishing) activity.finish() }
    }

    /** 这段时间内某个类的页面被打开了几个（"只开一次/开了两次"这类断言用它）。 */
    fun countPagesSince(clazz: Class<*>, since: Long): Int =
        watcher.since(since).count { clazz.isInstance(it.activity) }

    fun pagesSince(since: Long): List<Activity> = watcher.since(since).map { it.activity }

    /**
     * 起一个辅助线程（"等页面出现→替用户点返回"这类动作不能被主流程的等待挡住）。
     *
     * 辅助线程里的异常**不能让进程崩掉**（未捕获异常会直接杀进程，整轮回测就没了），
     * 因此这里统一抓起来，稍后由 [assertBackgroundOk]／等待超时的报错信息带出来。
     */
    fun background(block: () -> Unit): Thread =
        Thread({
            try {
                block()
            } catch (t: Throwable) {
                synchronized(backgroundErrors) { backgroundErrors.add(t) }
            }
        }, "bt-helper").apply { isDaemon = true; start() }

    /** 后台辅助动作是否出错（节点在等待完成后调用，把真实原因暴露出来）。 */
    fun assertBackgroundOk() {
        val errors = synchronized(backgroundErrors) { ArrayList(backgroundErrors) }
        if (errors.isNotEmpty()) {
            throw NodeFailure("后台辅助动作失败：${errors.first().javaClass.simpleName}: ${errors.first().message}")
        }
    }

    private fun backgroundErrorSuffix(): String {
        val errors = synchronized(backgroundErrors) { ArrayList(backgroundErrors) }
        if (errors.isEmpty()) return ""
        return "；后台辅助动作同时报错：${errors.first().javaClass.simpleName}: ${errors.first().message}"
    }

    /** 收到页面的 setResult 结果（模拟/驱动页面点击"返回并携带结果"）。 */
    fun finishPageWithResult(activity: Activity, resultCode: Int, data: android.content.Intent?) {
        main {
            activity.setResult(resultCode, data)
            activity.finish()
        }
    }

    /** 等待宿主页面重新回到前台（返回键/返回按钮的效果）。 */
    fun awaitHostBack(timeoutMs: Long = 6_000): Activity {
        // 关键：结果回调可能**先于**本调用到达（页面 finish、回调一回来，回测台就已经在前台了），
        // 此时"再等一次 resume 事件"会永远等不到——所以先看现状，再决定要不要等。
        if (host.hostResumed && !host.hostActivity.isFinishing) return host.hostActivity
        val mark = watcher.mark()
        return watcher.awaitResumed(host.hostActivity.javaClass, mark, timeoutMs)
            ?: throw NodeFailure("期望返回回测台，但实际停在：${watcher.describeSince(mark)}")
    }

    /** 等某个类的页面数量达到预期（页面切换是异步的，不能靠"睡固定时间"赌）。 */
    fun awaitPageCount(clazz: Class<*>, since: Long, expected: Int, timeoutMs: Long = 10_000): Int {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var count = countPagesSince(clazz, since)
        while (count < expected && SystemClock.elapsedRealtime() < deadline) {
            sleep(150)
            count = countPagesSince(clazz, since)
        }
        return count
    }

    /**
     * 收尾：把本节点打开的页面全部关掉，保证下一个节点从干净的前台开始。
     *
     * 只关"这个节点自己打开的页面"（[mark] 之后出现的），绝不碰其它东西——
     * 台子自己乱关页面会制造出"库有 bug"的假象（回测踩过：多关一次把回测台所在的任务关没了）。
     * 因为页面切换是异步的，最后一个页面可能在清理之后才切到前台，所以反复尝试若干轮。
     */
    fun closeAllPagesSince(mark: Long) {
        repeat(4) {
            val opened = watcher.since(mark).map { it.activity }.filter { it !== host.hostActivity }
            val top = watcher.current()
            val topBelongsToNode = top != null && opened.any { it === top }
            main {
                for (a in opened) if (!a.isFinishing) runCatching { a.finish() }
                if (topBelongsToNode && top != null && !top.isFinishing) runCatching { top.finish() }
            }
            sleep(250)
            if (host.hostResumed) return
        }
        // 本地页面关完还没回来，多半是"远端进程里的页面"还开着（回测台看不见也关不掉）
        restoreHostForeground()
    }

    /**
     * 确保回测台回到前台。
     *
     * 为什么需要它（回测踩过）：跨进程导航打开的页面住在**另一个进程**里，
     * 它留在屏幕上会把回测台压到后台；之后每个节点都会站在错误的前台上跑——
     * 表现是"库的行为变得怪怪的"，其实只是台子没把场景复位。
     *
     * 复位两步：先请两个远端进程把它们的页面关掉（等价于用户按返回），
     * 再把回测台重新提到最前。
     */
    fun restoreHostForeground() {
        if (host.hostResumed) return
        // 1) 远端页面自己关自己（不 await 太久：进程没起来时这里本来就没页面）
        for (target in listOf<String?>(null, REMOTE_TARGET_SECOND)) {
            val reply = runCatching {
                awaitCallback<String>(3_000, "远端收尾(${target ?: "默认"})") { cb ->
                    TRouter.callRemoteService(REMOTE_CLOSE_TOP, android.os.Bundle(), target) { cb(it) }
                }
            }.getOrNull()
            android.util.Log.i("TRouterBacktest", "BT|DIAG|远端收尾 target=${target ?: "默认"} reply=$reply")
        }
        // 2) 把回测台提到最前：CLEAR_TOP + SINGLE_TOP = 复用**已有的那个回测台实例**，
        //    并把它上面的页面关掉。用 REORDER_TO_FRONT 或裸 startActivity 会**再造一个回测台实例**，
        //    导致"宿主"和"屏幕上的那个"不是同一个（回测踩过：之后每个节点都站在错误的实例上）。
        main {
            val a = host.hostActivity
            runCatching {
                a.startActivity(
                    android.content.Intent(a, a.javaClass).addFlags(
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                )
            }
        }
        val deadline = SystemClock.elapsedRealtime() + 4_000
        while (!host.hostResumed && SystemClock.elapsedRealtime() < deadline) sleep(120)
        android.util.Log.i(
            "TRouterBacktest",
            "BT|DIAG|复位后 hostResumed=${host.hostResumed} 前台=${watcher.current()?.javaClass?.simpleName}",
        )
    }

    // ------------------------------------------------------------------ 宿主与全局状态

    fun hostActivity(): Activity = host.hostActivity

    fun lostPathsSnapshot(): List<String> = ArrayList(lostPaths)

    fun clearLostPaths() {
        lostPaths.clear()
    }

    /** 运行时挂一个拦截器，节点结束后自动摘掉（不影响后续节点）。 */
    fun addInterceptor(member: RouteChainMember): Boolean {
        val added = TRouter.addInterceptor(member)
        onCleanup { TRouter.removeInterceptor(member) }
        return added
    }

    fun logMark(): Int = RouteLog.size()

    fun logsSince(mark: Int): List<String> = RouteLog.since(mark)

    /** 在日志里找一条包含关键字的记录（找不到就报失败）。 */
    fun requireLog(mark: Int, needle: String, what: String): String {
        val line = logsSince(mark).firstOrNull { it.contains(needle) }
            ?: throw NodeFailure("日志里没有找到「$needle」（$what）；实际日志尾部：${logsSince(mark).takeLast(5)}")
        evidence += "日志证据：${line.take(160)}"
        return line
    }

    private companion object {
        /** 第二个远端进程的逻辑名（与 TRouterConfig.remoteServices 的 key 一致）。 */
        const val REMOTE_TARGET_SECOND: String = "remote2"

        /** 远端进程"把自己的页面关掉"的端点名（与 DemoProcessWiring 注册的一致）。 */
        const val REMOTE_CLOSE_TOP: String = "demoCloseTop"
    }
}
