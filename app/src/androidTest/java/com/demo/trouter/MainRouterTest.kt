package com.demo.trouter

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
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

    /** S01（R1·ACTIVITY / O1）：点击场景行 S01 → Second 页展示；日志含 navigate 出入口。 */
    @Test
    fun testBasicNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.scenario_s01)).perform(click())

            onView(withText("Second 页面")).check(matches(isDisplayed()))

            // O1：navigate 入口/出口日志
            val join = logs.lines.joinToString("\n")
            assertTrue("缺少 navigate 入口日志:\n$join", join.contains("[navigate][entry] traceId=") && join.contains("path=${RouterContract.PATH_SECOND}"))
            assertTrue("缺少 navigate 出口成功日志:\n$join", join.contains("[navigate][exit] traceId=") && join.contains("result=Success(meta=${RouterContract.PATH_SECOND}"))
        }
    }

    /** S02（R1·FRAGMENT / O1，A3 补全）：点击场景行 S02 → Fragment 演示页在 core 容器展示。 */
    @Test
    fun testFragmentNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.scenario_s02)).perform(click())

            onView(withText("Fragment 演示页面")).check(matches(isDisplayed()))

            val join = logs.lines.joinToString("\n")
            assertTrue("Fragment 跳转应记录 Success(kind=FRAGMENT):\n$join",
                join.contains("result=Success(meta=${RouterContract.PATH_FRAGMENT_DEMO}, kind=FRAGMENT)"))
        }
    }

    /** S03（C3 多 group / R1 / O1）：点击场景行 S03 → About 页展示；GroupLoader 日志含 secondary。 */
    @Test
    fun testSecondaryGroupNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.scenario_s03)).perform(click())

            onView(withText("About 页面（secondary group）")).check(matches(isDisplayed()))

            val join = logs.lines.joinToString("\n")
            assertTrue("应生成并加载 secondary group 的分组日志:\n$join",
                join.contains("[GroupLoader][load][start] group=secondary") &&
                    join.contains("[GroupLoader][load][end] group=secondary"))
            assertTrue("About 跳转应成功:\n$join",
                join.contains("result=Success(meta=${RouterContract.PATH_ABOUT}"))
        }
    }

    /** S04（R2 / O1）：点击场景行 S04 → 不崩溃，状态栏降级提示 + onLost 回调收到 path。 */
    @Test
    fun testLostNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.scenario_s04)).perform(click())

            // 降级 UI（MainActivity 状态文本）
            onView(withId(R.id.status_text))
                .check(matches(withText(containsString(RouterContract.PATH_UNREGISTERED))))

            // onLost 回调确收
            assertEquals(listOf(RouterContract.PATH_UNREGISTERED), lostPaths)

            // NotFound 出口日志
            val join = logs.lines.joinToString("\n")
            assertTrue("缺少 NotFound 出口日志:\n$join", join.contains("result=NotFound(path=${RouterContract.PATH_UNREGISTERED})"))
        }
    }

    /** S05（R3 / T1）：isDebug=true 日志完整输出（含 GroupLoader 与 navigate）；isDebug=false 静默但导航正常。 */
    @Test
    fun testConfigDebugMode() {
        // 阶段 A：默认 TestConfig isDebug=true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.scenario_s01)).perform(click())
            onView(withText("Second 页面")).check(matches(isDisplayed()))

            // O1：navigate 出入口 + GroupLoader 起止（setUp 阶段 install 产生）齐备
            val join = logs.lines.joinToString("\n")
            assertTrue("debug=true 应输出 GroupLoader 加载日志:\n$join", join.contains("[GroupLoader][load][start]") && join.contains("[GroupLoader][load][end]"))
            assertTrue("debug=true 应输出 navigate 出入口日志:\n$join", join.contains("[navigate][entry]") && join.contains("[navigate][exit]"))
            assertTrue("navigate 出口应带 traceId:\n$join", join.contains("traceId="))
        }

        // 阶段 B：切换 isDebug=false → 日志静默，但导航功能不受影响
        reInit(TestConfig(isDebug = false, logSink = logs))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.scenario_s01)).perform(click())
            onView(withText("Second 页面")).check(matches(isDisplayed()))
            assertEquals("isDebug=false 日志应静默", 0, logs.count())
        }
    }

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
            // 冷启动后逐屏上滑，直到 S11 行可见（深部行，scrollTo 在动画环境不稳，改用显式滚动）
            var s11Visible = false
            for (i in 1..12) {
                try {
                    onView(withId(R.id.scenario_s11)).check(matches(isDisplayed()))
                    s11Visible = true
                    break
                } catch (ignored: AssertionError) {
                    onView(withId(R.id.main_scroll)).perform(swipeUp())
                }
            }
            assertTrue("滚动后 S11 行应可见", s11Visible)
            onView(withId(R.id.scenario_s11)).perform(click())

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
        fun visibleClick(id: Int) {
            var visible = false
            for (i in 1..14) {
                try {
                    onView(withId(id)).check(matches(isDisplayed()))
                    visible = true
                    break
                } catch (ignored: AssertionError) {
                    onView(withId(R.id.main_scroll)).perform(swipeUp())
                }
            }
            assertTrue("滚动后行应可见 id=$id", visible)
            onView(withId(id)).perform(click())
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // 注册动态路由
            visibleClick(R.id.scenario_s13)
            onView(withId(R.id.status_text)).check(matches(withText(containsString("已注册动态路由:${RouterContract.PATH_DYNAMIC_DEMO}"))))

            // 导航动态路由 → DynamicDemo 页
            visibleClick(R.id.scenario_s14)
            onView(withText("DynamicDemo 页面")).check(matches(isDisplayed()))
            val joinSuccess = logs.lines.joinToString("\n")
            assertTrue("动态导航应 Success:\n$joinSuccess",
                joinSuccess.contains("result=Success(meta=${RouterContract.PATH_DYNAMIC_DEMO}"))

            // 返回主页，注销动态路由 → 再导航恢复 NotFound + onLost
            pressBack()
            visibleClick(R.id.scenario_s13)
            onView(withId(R.id.status_text)).check(matches(withText(containsString("已注销动态路由:${RouterContract.PATH_DYNAMIC_DEMO}"))))

            visibleClick(R.id.scenario_s14)
            onView(withId(R.id.status_text)).check(matches(withText(containsString(RouterContract.PATH_DYNAMIC_DEMO))))
            assertEquals(listOf(RouterContract.PATH_DYNAMIC_DEMO), lostPaths)
            val joinLost = logs.lines.joinToString("\n")
            assertTrue("注销后应 NotFound:\n$joinLost",
                joinLost.contains("result=NotFound(path=${RouterContract.PATH_DYNAMIC_DEMO}"))
        }
    }
}
