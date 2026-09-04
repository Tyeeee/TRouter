package com.trouter.core.api

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 页面系统栏避让小工具（随 core 分发给任意宿主/feature 模块页面复用，避免跨模块复制）：
 * edge-to-edge（targetSdk≥35）下给根视图叠加状态栏/导航栏 inset。
 *
 * 用法：先给 [root] 设置好左右/底部基础 padding，再调用 [applyEdgeInsets]；
 * 该工具会在基础 padding 之上叠加系统栏高度，避免内容被遮挡。
 */
object Ui {

    fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    fun applyEdgeInsets(root: View, activity: Activity, extraTopDp: Int = 0, extraBottomDp: Int = 0) {
        val baseTop = root.paddingTop
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                baseTop + bars.top + dp(activity, extraTopDp),
                view.paddingRight,
                baseBottom + bars.bottom + dp(activity, extraBottomDp),
            )
            insets
        }
    }
}
