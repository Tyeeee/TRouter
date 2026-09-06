package com.demo.trouter

import android.content.ComponentName
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.demo.trouter.generated.CrossProcessPaths
import com.trouter.core.api.DemoParams
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouterConfig
import com.trouter.core.internal.RemoteRouterService
import com.trouter.core.testing.BaseTRouterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.containsString

/**
 * 参数透传测试（单进程 Activity / Fragment / 跨进程 AIDL），场景 S19/S20/S21。
 * 核心断言：调用方 bundle 的 msg/count 真正送达目标页并取值正确。
 */
@RunWith(AndroidJUnit4::class)
class TRouterParamPassingTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    private val expectedText = "参数透传 ✓ msg=来自主页的参数字符串 · count=42"

    /** S19（单进程 Activity 参数 + 保留键防覆盖）：点击带 bundle 的 S19 → Second 页展示参数，且路由元数据未被 bundle 冒名。 */
    @Test
    fun activityParamsDeliveredToSecond() {
        ActivityScenario.launch(MainActivity::class.java).use {
            DemoUiClicks.visibleClick(R.id.scenario_s19)
            onView(withText(expectedText)).check(matches(isDisplayed()))
            onView(withText(containsString("· path=/second · kind="))).check(matches(isDisplayed())) // 保留键仍为真实 path
        }
    }

    /** S20（单进程 Fragment 参数 + 保留键防覆盖）：Fragment 页展示参数，路由元数据未被覆盖。 */
    @Test
    fun fragmentParamsDelivered() {
        ActivityScenario.launch(MainActivity::class.java).use {
            DemoUiClicks.visibleClick(R.id.scenario_s20)
            onView(withText(expectedText)).check(matches(isDisplayed()))
            onView(withText(containsString("· path=/fragment-demo · kind="))).check(matches(isDisplayed()))
        }
    }

    /** S21（跨进程参数）：点击带 bundle 的 S21 → AIDL 送达 :remote；服务回包携带 params echo，host 日志断言参数原样到达。 */
    @Test
    fun remoteParamsDeliveredCrossProcess() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        reInit(
            TRouterConfig(
                isDebug = true,
                logSink = logs,
                onLost = { lostPaths += it },
                remoteService = ComponentName(ctx, RemoteRouterService::class.java),
                remoteWhitelist = CrossProcessPaths.paths,
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            DemoUiClicks.visibleClick(R.id.scenario_s21)

            // 等待携带 params echo 的 recv 日志（跨进程冷启动 <20s）
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline &&
                logs.lines.none { it.contains("[remote][recv]") && it.contains("params=[") }
            ) {
                Thread.sleep(200)
            }

            val join = logs.lines.joinToString("\n")
            assertTrue("recv 应携带 params echo:\n$join",
                join.contains("[remote][recv]") && join.contains("params=["))
            assertTrue("msg 应原样到达 :remote:\n$join", join.contains("demo.param.msg=来自主页的参数字符串"))
            assertTrue("count 应原样到达 :remote:\n$join", join.contains("demo.param.count=42"))
            assertTrue("保留键同名用户参数也应原样跨进程（传输层不改写）:\n$join",
                join.contains("com.trouter.core.extra.PATH=HACKED-BUNDLE-OVERRIDE"))
        }
    }
}
