package com.demo.trouter.backtest

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry

/**
 * 回测台的测试会话：**像用户一样把回测台页面打开**，用完再关掉。
 *
 * 为什么不用 ActivityScenario（回测踩过）：
 * ActivityScenario 在关闭时会往任务栈里塞一个自己的 `EmptyActivity` 占位页，
 * 而回测台本身要观测整条任务栈（谁在前台、上面还压着谁）。
 * 那个占位页会被观测器当成"一个真实的页面"，把结论带偏；
 * 用 Instrumentation 直接打开页面，栈就是"用户打开页面"时该有的样子。
 */
class ConsoleSession private constructor(private val activity: Activity) {

    val console: BacktestConsoleActivity get() = activity as BacktestConsoleActivity

    fun close() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching { instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish() } }
        // 给它一点时间真正退出，避免影响下一个用例
        Thread.sleep(300)
    }

    companion object {
        fun open(): ConsoleSession {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val intent = Intent(instrumentation.targetContext, BacktestConsoleActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val activity = instrumentation.startActivitySync(intent)
            check(activity is BacktestConsoleActivity) {
                "期望打开回测台，实际打开了 ${activity.javaClass.name}"
            }
            // 等它真的到前台，后面的点击才有落点
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (!activity.hasWindowFocus() && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(50)
            }
            return ConsoleSession(activity)
        }
    }
}
