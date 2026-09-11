package com.demo.trouter.backtest

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.demo.trouter.R
import org.hamcrest.Matchers.containsString
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「模拟实际操作」的界面级回测：全部用真实的手势与输入。
 *
 * 与节点回测的区别：这里没有一行代码去调用页面的内部方法，
 * 全部是 Espresso 真的在输入框里打字、真的点按钮、真的点返回，
 * 然后核对**下一个页面屏幕上显示出来的文字**。
 * 它回答的问题是："一个真实用户这样操作，库能不能用？"
 */
@RunWith(AndroidJUnit4::class)
class BacktestOperationUiTest {

    /**
     * 人的入口：从主页点 S30 那一行进回测台。
     *
     * 回测台自己是被路由注册的页面，这一条同时验证"人点得到"和"回测台是可路由页面"。
     */
    @Test
    fun openConsoleFromMainPageRowS30() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val intent = android.content.Intent(instrumentation.targetContext, com.demo.trouter.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        // 复用工程里现成的"滚到行再点"工具（主页行很多，需要滚动）
        com.demo.trouter.DemoUiClicks.visibleClick(R.id.scenario_s30)

        onView(withId(R.id.backtest_summary)).check(matches(isDisplayed()))
        onView(withId(R.id.backtest_run_all)).check(matches(isDisplayed()))
    }

    /** 从回测台点开参数操作页：页面要能显示自己是路由打开的、并且收到了参数。 */
    @Test
    fun openFormPageFromConsoleAndSeeParams() {
        val session = ConsoleSession.open()
        try {
            onView(withId(R.id.backtest_open_form)).perform(click())
            onView(withId(R.id.form_evidence))
                .check(matches(withText(containsString("参数透传 ✓"))))
                .check(matches(withText(containsString("回测台手动传参"))))
        } finally {
            session.close()
        }
    }

    /** 真实用户全流程：打字 → 点跳转 → 在下一页看到自己输入的值 → 点返回 → 回到表单页看到成功。 */
    @Test
    fun typeParamsThenTapSyncNavigateAndVerifyNextPage() {
        val session = ConsoleSession.open()
        try {
            onView(withId(R.id.backtest_open_form)).perform(click())

            onView(withId(R.id.form_msg)).perform(replaceText("UI 输入的参数"), closeSoftKeyboard())
            onView(withId(R.id.form_count)).perform(replaceText("45"), closeSoftKeyboard())
            onView(withId(R.id.form_go_sync)).perform(click())

            // 下一页真的把我输入的值显示了出来
            onView(withText("参数透传 ✓ msg=UI 输入的参数 · count=45"))
                .check(matches(isDisplayed()))

            onView(withText("返回")).perform(click())

            // 回到表单页，页面上显示这次跳转成功
            onView(withId(R.id.form_result)).check(matches(withText(containsString("跳转成功"))))
        } finally {
            session.close()
        }
    }

    /** 真实用户走一条不存在的路径：页面上给出"未找到"，而不是白屏或崩溃。 */
    @Test
    fun tapNotFoundPathShowsNotFoundOnPage() {
        val session = ConsoleSession.open()
        try {
            onView(withId(R.id.backtest_open_form)).perform(click())
            onView(withId(R.id.form_go_notfound)).perform(click())
            onView(withId(R.id.form_result)).check(matches(withText(containsString("未找到路径"))))
        } finally {
            session.close()
        }
    }

    /** 从回测台直接打开 /second：真实页面出现，并且页面上写明它是被路由打开的。 */
    @Test
    fun openSecondFromConsoleShowsRouteEvidence() {
        val session = ConsoleSession.open()
        try {
            onView(withId(R.id.backtest_open_second)).perform(click())
            onView(withText(containsString("本次由 TRouter 打开"))).check(matches(isDisplayed()))
            onView(withText(containsString("path=/second"))).check(matches(isDisplayed()))
        } finally {
            session.close()
        }
    }
}
