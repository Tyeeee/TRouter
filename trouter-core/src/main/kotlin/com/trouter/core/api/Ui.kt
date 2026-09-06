package com.trouter.core.api

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 页面系统栏避让/文本脚手架小工具（随 core 分发给任意宿主/feature 模块页面复用，避免跨模块复制）。
 */
object Ui {

    // 语义色（demo 页横幅/证据文本统一取色，避免各页散落 rgb 魔法数）
    const val COLOR_TEXT_GRAY: Int = 0xFF666666.toInt()
    const val COLOR_TITLE_BLUE: Int = 0xFF0066CC.toInt()
    const val COLOR_EVIDENCE_GREEN: Int = 0xFF1B7F3B.toInt()
    const val COLOR_REMOTE_PURPLE: Int = 0xFF800080.toInt()
    const val COLOR_ERROR_RED: Int = 0xFFC83C3C.toInt()

    /** 统一「说明行」TextView 构造（各目标页 line() 脚手架的单一实现）。 */
    fun lineText(
        context: Context,
        text: String,
        size: Float = 14f,
        color: Int = COLOR_TEXT_GRAY,
    ): TextView = TextView(context).apply {
        this.text = text
        this.textSize = size
        setTextColor(color)
        setPadding(0, 4, 0, 4)
    }

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
