package com.demo.trouter

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import kotlin.math.max

/**
 * 测试台 UI 点击共用工具（各 UI 用例复用，避免每类复制一套“滚动到行再点”逻辑）。
 *
 * 策略：先整屏上滑若干次（覆盖深部行），再用 ViewAction 把 ScrollView 精确滚动到目标行
 * 居中可见，最后执行点击；若仍失败则交替微滚重试。
 */
object DemoUiClicks {

    private val SCROLL_ID = R.id.main_scroll

    fun visibleClick(id: Int) {
        // 先整屏滚动，尽量把目标带进视口（对很深的行）
        for (i in 1..4) {
            onView(withId(SCROLL_ID)).perform(swipeUp())
        }
        centerScrollTo(id)
        var last: Throwable? = null
        for (i in 1..6) {
            try {
                onView(withId(id)).perform(click())
                return
            } catch (t: Throwable) {
                last = t
                onView(withId(SCROLL_ID)).perform(swipeUp())
            }
        }
        throw AssertionError("滚动定位后仍无法点击行 id=$id", last)
    }

    /** 把 ScrollView 精确滚动到目标行竖直居中（消除“露出一角但中心在屏外”的情形）。 */
    private fun centerScrollTo(id: Int) {
        onView(withId(SCROLL_ID)).perform(object : ViewAction {
            override fun getConstraints() = isAssignableFrom(View::class.java)

            override fun getDescription(): String = "把行 $id 滚动到 ScrollView 竖直居中"

            override fun perform(uiController: UiController, view: View) {
                uiController.loopMainThreadUntilIdle()
                val scroll = view as android.widget.ScrollView
                val target = scroll.findViewById<View>(id) ?: return
                val targetY = target.top - (scroll.height - target.height) / 2
                scroll.scrollTo(0, max(0, targetY))
                uiController.loopMainThreadUntilIdle()
            }
        })
    }
}
