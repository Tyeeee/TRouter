package com.demo.trouter

import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.RouteArgs
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult
import com.trouter.core.testing.BaseTRouterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.containsString

/**
 * 拿页面返回值 导航结果回调 + 取参数的小助手 收参助手测试（批 2 首两件）。
 */
@RunWith(AndroidJUnit4::class)
class TRouterResultAndArgsTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    /** 拿页面返回值-1：无前台 Activity 时 navigateForResult 明确 Blocked（不偷偷降级为 NEW_TASK）。 */
    @Test
    fun navigateForResultWithoutForegroundIsBlocked() {
        // 本用例未启动任何 Activity（BaseTRouterTest 只有 init/install）
        val result = TRouter.navigateForResult(RouterContract.PATH_RESULT_DEMO, requestCode = 1001)
        assertTrue("应 Blocked: $result", result is TRouterResult.Blocked)
        assertTrue("reason 应说明需要前台 Activity: ${(result as TRouterResult.Blocked).reason}",
            result.reason.contains("前台 Activity"))
        assertTrue("onLost 不应触发", lostPaths.isEmpty())
    }

    /** 拿页面返回值-2（UI 全链路）：S22 → Result 页 → 「返回并携带结果」→ 主页状态栏收到回传数据。 */
    @Test
    fun resultEchoRoundTripViaUI() {
        ActivityScenario.launch(MainActivity::class.java).use {
            DemoUiClicks.visibleClick(R.id.scenario_s22)
            onView(withText("返回并携带结果")).check(matches(isDisplayed()))
            onView(withText("返回并携带结果")).perform(click())
            onView(withId(R.id.status_text)).check(
                matches(withText(containsString("收到返回结果: 来自 Result 页的返回数据"))),
            )
        }
    }

    /** 取参数的小助手-1：RouteArgs 类型化收参（String/数值/布尔/数组/Serializable）。 */
    @Test
    fun routeArgsTypedReads() {
        val payload = SerializablePayload(7)
        val bundle = Bundle().apply {
            putString("k_str", "你好")
            putInt("k_int", 42)
            putLong("k_long", 9L)
            putDouble("k_double", 3.14)
            putBoolean("k_bool", true)
            putStringArrayList("k_list", arrayListOf("a", "b"))
            putSerializable("k_obj", payload)
        }
        val args = RouteArgs.of(bundle)
        assertEquals("你好", args.str("k_str"))
        assertEquals(42, args.int("k_int"))
        assertEquals(9L, args.long("k_long"))
        assertEquals(3.14, args.double("k_double"), 1e-9)
        assertTrue(args.boolean("k_bool"))
        assertEquals(listOf("a", "b"), args.strings("k_list"))
        assertEquals(payload, args.serializable("k_obj"))
        assertTrue(args.contains("k_str"))
        assertEquals("缺省默认值生效", "缺省", args.str("k_missing", "缺省"))
        assertEquals(0, args.int("k_missing2"))
    }

    /** 取参数的小助手-2：Intent 便捷入口 + 空参数安全。 */
    @Test
    fun routeArgsIntentEntryAndEmptySafe() {
        val intent = Intent().putExtra("x", 1)
        assertEquals(1, RouteArgs.of(intent).int("x"))
        assertTrue(RouteArgs.of(null as Intent?).isEmpty)
        assertNull(RouteArgs.of(null as Intent?).str("nope"))
    }

    private class SerializablePayload(val value: Int) : java.io.Serializable
}
