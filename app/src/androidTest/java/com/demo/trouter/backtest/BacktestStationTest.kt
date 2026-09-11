package com.demo.trouter.backtest

import android.os.SystemClock
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.demo.trouter.R
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 回测台驱动器（设备内自动化）：用真实点击驱动回测台，跑完所有节点并核对结论。
 *
 * 它**不替代**节点本身的工作——节点才是"真的去跳转、真的去看屏幕"的那部分。
 * 本类负责的是另一件事：证明"人点一下就能跑完"这条路也走得通（按钮、进度、结论显示都是真的）。
 */
@RunWith(AndroidJUnit4::class)
class BacktestStationTest {

    /** 一键回测：点「开始回测」，等全部节点跑完，要求零失败，并要求界面结论与报告一致。 */
    @Test
    fun runAllNodesFromConsoleAndRequireZeroFailure() {
        val session = ConsoleSession.open()
        try {
            val previous = BacktestStation.lastReport
            onView(withId(R.id.backtest_run_all)).perform(click())
            val report = awaitReport(previous)

            // 界面上必须显示同一份结论（避免"报告绿了界面骗人"）
            onView(withId(R.id.backtest_summary))
                .check(matches(withText(containsString("通过 ${report.passCount}"))))

            assertEquals(
                "回测未全部通过：\n" + report.toText(),
                0,
                report.failCount,
            )
        } finally {
            session.close()
        }
    }

    /** 单节点重跑：点某一行自己的「跑」按钮，只跑那一个节点，结论写回那一行。 */
    @Test
    fun singleNodeCanBeRerunFromItsOwnRow() {
        val session = ConsoleSession.open()
        try {
            val previous = BacktestStation.lastReport
            val nodeId = "A01"
            onView(withTagValue(equalTo(BacktestConsoleActivity.runTag(nodeId) as Any)))
                .perform(scrollTo(), click())
            val report = awaitReport(previous)

            assertEquals("单节点模式应只跑 1 个节点", 1, report.total)
            assertEquals("单节点重跑未通过：\n" + report.toText(), 0, report.failCount)
            onView(withTagValue(equalTo(BacktestConsoleActivity.statusTag(nodeId) as Any)))
                .perform(scrollTo())
                .check(matches(withText("通过")))
        } finally {
            session.close()
        }
    }

    /** 等新一轮回测结束（以"报告对象换了新的"为准，避免读到上一轮结果）。 */
    private fun awaitReport(previous: BacktestReport?): BacktestReport {
        val deadline = SystemClock.elapsedRealtime() + 20 * 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val report = BacktestStation.lastReport
            if (report != null && report !== previous && !BacktestStation.running) return report
            Thread.sleep(500)
        }
        fail("回测在 20 分钟内没有结束")
        error("unreachable")
    }
}
