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

    private fun visibleClick(id: Int) {
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

    private val expectedText = "参数透传 ✓ msg=来自主页的参数字符串 · count=42"

    /** S19（单进程 Activity 参数）：点击带 bundle 的 S19 → Second 页展示收到的参数。 */
    @Test
    fun activityParamsDeliveredToSecond() {
        ActivityScenario.launch(MainActivity::class.java).use {
            visibleClick(R.id.scenario_s19)
            onView(withText(expectedText)).check(matches(isDisplayed()))
        }
    }

    /** S20（单进程 Fragment 参数）：点击带 bundle 的 S20 → Fragment 页展示收到的参数。 */
    @Test
    fun fragmentParamsDelivered() {
        ActivityScenario.launch(MainActivity::class.java).use {
            visibleClick(R.id.scenario_s20)
            onView(withText(expectedText)).check(matches(isDisplayed()))
        }
    }

    /** S21（跨进程参数）：点击带 bundle 的 S21 → AIDL 送达 :remote，remote 页把参数写入跨进程共享 prefs 文件，host 侧直接读文件校验。 */
    @Test
    fun remoteParamsDeliveredCrossProcess() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // 直接读写 shared_prefs 文件：SharedPreferences 实例按进程缓存，跨进程读文件最可靠
        val echoFile = java.io.File(ctx.dataDir, "shared_prefs/${DemoParams.PREF_NAME}.xml")
        echoFile.delete()
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
            visibleClick(R.id.scenario_s21)

            fun readEcho(): String? = runCatching { echoFile.readText() }.getOrNull()
            fun echoHasPath(): Boolean = readEcho()?.contains("\"${DemoParams.PREF_LAST_PATH}\">${RouterContract.PATH_REMOTE_SECOND}") == true

            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && !echoHasPath()) {
                Thread.sleep(200)
            }

            val xml = readEcho()
            assertTrue("remote 页应已写入参数回读文件（当前: $xml）", xml != null)
            assertTrue("path 应正确", xml!!.contains("\"${DemoParams.PREF_LAST_PATH}\">${RouterContract.PATH_REMOTE_SECOND}"))
            assertTrue("msg 应正确", xml.contains("\"${DemoParams.PREF_LAST_MSG}\">来自主页的参数字符串"))
            assertTrue("count 应正确", xml.contains("\"${DemoParams.PREF_LAST_COUNT}\" value=\"42\""))

            val join = logs.lines.joinToString("\n")
            assertTrue("应有跨进程回包:\n$join", join.contains("[remote][recv]"))
            echoFile.delete()
        }
    }
}
