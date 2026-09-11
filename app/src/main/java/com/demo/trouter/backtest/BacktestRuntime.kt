package com.demo.trouter.backtest

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 库日志的内存环形缓冲。
 *
 * 为什么需要它：TRouter 的可观测性输出（`[navigate][entry]` `[remote][recv] ... params=[...]`
 * `[GroupLoader][load]` 等）本身就是**契约的一部分**（README 第七章"出问题了怎么查"）。
 * 回测如果只看返回值，就没验证到"日志真的打出来了"。
 *
 * 宿主把 config.logSink 接到这里（见 DemoRouterConfig），于是：
 * - logcat 依旧照常输出（sink 内部同时写 android.util.Log）；
 * - 回测节点可以就地断言日志内容，自动化用例也能在设备内自证。
 */
object RouteLog {

    private const val CAPACITY = 8000
    private val buffer = ArrayDeque<String>()

    @Synchronized
    fun append(line: String) {
        if (buffer.size >= CAPACITY) buffer.removeFirst()
        buffer.addLast(line)
    }

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun clear() {
        buffer.clear()
    }

    @Synchronized
    fun firstOrNull(needle: String): String? = buffer.firstOrNull { it.contains(needle) }

    @Synchronized
    fun allContain(vararg needles: String): Boolean = needles.all { n -> buffer.any { it.contains(n) } }

    /** 从某次标记之后新增的日志（配合 [size] 使用，避免与上一节点的日志混淆）。 */
    @Synchronized
    fun size(): Int = buffer.size

    @Synchronized
    fun since(mark: Int): List<String> = buffer.toList().drop(mark)
}

/** 一次「某个 Activity 被切到前台」的观测事件。 */
data class ResumeEvent(val seq: Long, val activity: Activity, val atMs: Long)

/**
 * 真实页面观测器：监听 Activity 生命周期，记录**真的被打开的页面**。
 *
 * 这是回测"看效果"的核心工具——断言目标不再是 `navigate()` 的返回值，
 * 而是"屏幕上真的出现了哪个页面、它真实拿到的 intent/Fragment 参数是什么"。
 */
class ActivityWatcher(private val app: Application) : Application.ActivityLifecycleCallbacks {

    private val lock = Object()
    private val events = ArrayList<ResumeEvent>()
    private var counter = 0L

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
    }

    fun stop() {
        app.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        synchronized(lock) {
            counter += 1
            events.add(ResumeEvent(counter, activity, android.os.SystemClock.elapsedRealtime()))
            lock.notifyAll()
        }
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    /** 当前序号：节点开始时先取一个，之后的等待都只看"这之后"的事件。 */
    fun mark(): Long = synchronized(lock) { counter }

    fun since(mark: Long): List<ResumeEvent> = synchronized(lock) { events.filter { it.seq > mark } }

    fun current(): Activity? = synchronized(lock) {
        events.lastOrNull { !it.activity.isFinishing }?.activity
    }

    /**
     * 等待某个类**真的**被切到前台。
     * @return 命中的 Activity；超时返回 null（调用方据此报失败并给出实际观测到的页面清单）。
     */
    fun awaitResumed(clazz: Class<*>, sinceMark: Long, timeoutMs: Long): Activity? {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        synchronized(lock) {
            while (true) {
                events.firstOrNull { it.seq > sinceMark && clazz.isInstance(it.activity) }?.let { return it.activity }
                val remain = deadline - android.os.SystemClock.elapsedRealtime()
                if (remain <= 0) return null
                lock.wait(remain.coerceAtMost(200L))
            }
        }
    }

    /** 等待这之后出现的第一个页面（不限类型）。 */
    fun awaitAnyResumed(sinceMark: Long, timeoutMs: Long): Activity? {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        synchronized(lock) {
            while (true) {
                events.firstOrNull { it.seq > sinceMark }?.let { return it.activity }
                val remain = deadline - android.os.SystemClock.elapsedRealtime()
                if (remain <= 0) return null
                lock.wait(remain.coerceAtMost(200L))
            }
        }
    }

    /** 观测到的实际页面清单（失败时写进证据，便于判断"到底打开了什么"）。 */
    fun describeSince(mark: Long): String {
        val list = since(mark)
        if (list.isEmpty()) return "（这段时间内没有任何页面被切到前台）"
        return list.joinToString(" → ") { it.activity.javaClass.simpleName }
    }
}

/**
 * 回测宿主（由回测台 Activity 实现）。
 *
 * 把"需要站在某个前台 Activity 上才能做的事"（典型：navigateForResult 必须由前台页面发起）
 * 从节点里抽出来，交给宿主页面执行并把结果送回来。
 */
interface BacktestHost {

    /** 宿主页面本身（回测台）。 */
    val hostActivity: Activity

    /** 当前宿主是否在前台（navigateForResult 的前提）。 */
    val hostResumed: Boolean

    /**
     * 由宿主页面发起一次"要返回值"的跳转；返回值经宿主的 onActivityResult 回来后调用 [onResult]。
     * @return TRouter.navigateForResult 的即时结果
     */
    fun launchForResult(
        path: String,
        requestCode: Int,
        bundle: Bundle?,
        onResult: (resultCode: Int, data: android.content.Intent?) -> Unit,
    ): com.trouter.core.api.TRouterResult

    /** 节点开始/结束的进度回调（更新回测台界面）。 */
    fun onNodeStart(node: BacktestNode, index: Int, total: Int)

    fun onNodeFinished(result: NodeResult)

    fun onReport(report: BacktestReport)

    /** 运行期日志（显示在回测台底部，也是人工排查的入口）。 */
    fun onLog(line: String)
}
