package com.demo.trouter

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouterConfig
import com.trouter.core.testing.BaseTRouterTest
import com.trouter.core.testing.TestConfig
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.containsString

/**
 * UI 行为驱动 · 拦截器场景（S08 门禁 Block / S09 Mock Redirect，见 docs/拦截器版本-拦截器方案.md §7）。
 *
 * 与 MainRouterTest（最早的基础版本 十例，默认无拦截器配置）隔离：本类 createConfig 注入 DemoInterceptors
 * 的完整演示列表（gate + mock），用真实点击驱动「开/关开关 → 导航 S01 → 断言反馈/页面/日志」。
 * 开关为共享有状态实例，故每个用例开始/结束都复位（T2 零污染）。
 */
@RunWith(AndroidJUnit4::class)
class TRouterInterceptorUiTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    override fun createConfig(): TRouterConfig =
        TestConfig(
            isDebug = true,
            logSink = logs,
            onLost = { lostPaths += it },
            interceptors = DemoInterceptors.demoList,
        )

    @After
    fun resetDemoSwitches() {
        DemoInterceptors.gate.enabled = false
        DemoInterceptors.mock.enabled = false
    }

    /** S08（C2/C3）：门禁开启 → S01 被 Blocked（Second 不出现）；关闭 → 恢复放行。 */
    @Test
    fun testGateBlockThenRelease() {
        DemoInterceptors.gate.enabled = false
        DemoInterceptors.mock.enabled = false

        ActivityScenario.launch(MainActivity::class.java).use {
            // 开启门禁
            onView(withId(R.id.scenario_s08)).perform(scrollTo(), click())
            onView(withId(R.id.status_text)).check(matches(withText(containsString("门禁拦截器：已开启"))))

            // 导航 /second → Blocked：不打开 Second，状态栏回显「已拦截」
            onView(withId(R.id.scenario_s01)).perform(scrollTo(), click())
            onView(withId(R.id.status_text)).check(matches(withText(containsString("已拦截"))))
            onView(withText("Second 页面")).check(doesNotExist())

            val join = logs.lines.joinToString("\n")
            assertTrue("出口应为 Blocked:\n$join", join.contains("result=Blocked(path=${RouterContract.PATH_SECOND}"))
            assertTrue("拦截器 eval 应含 Block:\n$join", join.contains("decision=Block("))

            // 关闭门禁 → S01 放行，真实 Second 打开
            onView(withId(R.id.scenario_s08)).perform(scrollTo(), click())
            onView(withId(R.id.status_text)).check(matches(withText(containsString("门禁拦截器：已关闭"))))
            onView(withId(R.id.scenario_s01)).perform(scrollTo(), click())
            onView(withText("Second 页面")).check(matches(isDisplayed()))
        }
    }

    /** S09（C4）：Mock 开启 → S01 打开 Mock 页（真实 Second 不出现）；关闭 → 恢复真实页。 */
    @Test
    fun testMockRedirectOnThenOff() {
        DemoInterceptors.gate.enabled = false
        DemoInterceptors.mock.enabled = false

        ActivityScenario.launch(MainActivity::class.java).use {
            // 开启 Mock
            onView(withId(R.id.scenario_s09)).perform(scrollTo(), click())
            onView(withId(R.id.status_text)).check(matches(withText(containsString("Mock 拦截器：已开启"))))

            // 导航 /second → 被 Redirect 到 Mock 页
            onView(withId(R.id.scenario_s01)).perform(scrollTo(), click())
            onView(withText("Mock Second 页面")).check(matches(isDisplayed()))
            onView(withText("Second 页面")).check(doesNotExist())

            val join = logs.lines.joinToString("\n")
            assertTrue("原跳出口应记 Redirect:\n$join", join.contains("result=Redirect(target=${RouterContract.PATH_MOCK_SECOND})"))
            assertTrue("最终出口应为 Mock 目标 Success:\n$join", join.contains("result=Success(meta=${RouterContract.PATH_MOCK_SECOND}"))

            // 返回主页，关闭 Mock → 再点 S01 恢复真实 Second 页
            onView(withText("返回")).perform(click())
            onView(withId(R.id.scenario_s09)).perform(scrollTo(), click())
            onView(withId(R.id.status_text)).check(matches(withText(containsString("Mock 拦截器：已关闭"))))
            onView(withId(R.id.scenario_s01)).perform(scrollTo(), click())
            onView(withText("Second 页面")).check(matches(isDisplayed()))
        }
    }
}
