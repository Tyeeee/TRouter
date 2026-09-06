package com.demo.trouter

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.ComponentName
import com.demo.trouter.generated.CrossProcessPaths
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouterConfig
import com.trouter.core.internal.RemoteRouterService
import com.trouter.core.testing.BaseTRouterTest
import com.trouter.core.testing.TestConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.containsString

/**
 * UI 行为驱动场景用例（场景体系见 docs/V1.0-测试与演示台规划.md §2.1）。
 *
 * 本类 = UI 可驱动场景（S01–S05）：统一由 BaseTRouterTest init(TestConfig)+install，
 * 用例只做「点击场景行 → 断言目标页/反馈/日志」的真实操作，不直调 TRouter.navigate。
 * 行 id 规则：scenario_s{场景号}（见 ids.xml 与 MainActivity）。
 */
@RunWith(AndroidJUnit4::class)
class MainRouterTest : BaseTRouterTest() {

    /** :app 的 KSP 生成注册表（本模块可见）。 */
    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    /** S11（C1/C5/T1）：真实点击「S11 跨进程导航」→ AIDL 达 :remote 进程，远端 TRouter 打开并异步回传。 */
    @Test
    fun testRemoteProcessNavigation() {
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            DemoUiClicks.visibleClick(R.id.scenario_s11)

            // 异步通道：轮询等待远端回包日志（跨进程冷启动拉起 ≤20s）
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline &&
                logs.lines.none { it.contains("[remote][recv]") }
            ) {
                Thread.sleep(200)
            }

            val join = logs.lines.joinToString("\n")
            assertTrue("应记录远端发送日志:\n$join",
                join.contains("[remote][send]") && join.contains("path=${RouterContract.PATH_REMOTE_SECOND}"))
            assertTrue("应记录远端接收成功日志:\n$join",
                join.contains("[remote][recv]") && join.contains("result=Success(meta=${RouterContract.PATH_REMOTE_SECOND}"))
            assertTrue("跨进程成功不应触发 onLost", lostPaths.isEmpty())
        }
    }

    /** S13（V5.0）：真实点击——注册动态路由 → 导航成功；注销 → 导航走 NotFound 降级。 */
    @Test
    fun testDynamicRouteScenario() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // 注册动态路由
            DemoUiClicks.visibleClick(R.id.scenario_s13)
            onView(withId(R.id.status_text)).check(matches(withText(containsString("已注册动态路由:${RouterContract.PATH_DYNAMIC_DEMO}"))))

            // 导航动态路由 → DynamicDemo 页
            DemoUiClicks.visibleClick(R.id.scenario_s14)
            onView(withText("DynamicDemo 页面")).check(matches(isDisplayed()))
            val joinSuccess = logs.lines.joinToString("\n")
            assertTrue("动态导航应 Success:\n$joinSuccess",
                joinSuccess.contains("result=Success(meta=${RouterContract.PATH_DYNAMIC_DEMO}"))

            // 返回主页，注销动态路由 → 再导航恢复 NotFound + onLost
            pressBack()
            DemoUiClicks.visibleClick(R.id.scenario_s13)
            onView(withId(R.id.status_text)).check(matches(withText(containsString("已注销动态路由:${RouterContract.PATH_DYNAMIC_DEMO}"))))

            DemoUiClicks.visibleClick(R.id.scenario_s14)
            onView(withId(R.id.status_text)).check(matches(withText(containsString(RouterContract.PATH_DYNAMIC_DEMO))))
            assertEquals(listOf(RouterContract.PATH_DYNAMIC_DEMO), lostPaths)
            val joinLost = logs.lines.joinToString("\n")
            assertTrue("注销后应 NotFound:\n$joinLost",
                joinLost.contains("result=NotFound(path=${RouterContract.PATH_DYNAMIC_DEMO}"))
        }
    }
}
