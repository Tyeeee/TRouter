package com.demo.trouter

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.trouter.core.api.RouterContract
import com.trouter.core.api.RouteLaunch
import com.trouter.core.api.Ui

/**
 * S13 动态路由目标页（V5.0）。
 *
 * 注意：本页**没有标注 @Route**——它由运行时 `TRouter.registerRoute(...)` 动态注册到路由表，
 * 证明动态路由不依赖 KSP 生成物；页面类仍只存字符串、惰性加载（Class.forName 发生在 navigate 时）。
 */
class DynamicDemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fun line(text: String, size: Float = 14f, color: Int = Color.rgb(102, 102, 102)) =
            TextView(this).apply {
                this.text = text
                this.textSize = size
                setTextColor(color)
                setPadding(0, 4, 0, 4)
            }

        val d = Ui.dp(this, 1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24 * d, 0, 24 * d, 24 * d)
        }

        val path = intent.getStringExtra(RouteLaunch.EXTRA_PATH)
        val runtime = if (path != null) {
            "✓ 本次由 TRouter 打开 · path=$path · group=${intent.getStringExtra(RouteLaunch.EXTRA_GROUP)} · " +
                "kind=${intent.getStringExtra(RouteLaunch.EXTRA_KIND)} · traceId=${intent.getStringExtra(RouteLaunch.EXTRA_TRACE_ID)}"
        } else {
            "（直连/系统启动：本页未带 TRouter 路由元数据）"
        }

        root.addView(line("场景 S13 · 动态路由目标页（运行时注册）", 15f, Color.rgb(0, 102, 204)))
        root.addView(line("路径 /dynamic-demo · group=dynamic · kind=ACTIVITY · 本页未标注 @Route"))
        root.addView(line("期望：由 TRouter.registerRoute 注册后，navigate 打开本页；注销后回到 NotFound"))
        root.addView(line(runtime, 14f, Color.rgb(27, 127, 59)))
        root.addView(TextView(this).apply {
            text = "DynamicDemo 页面"
            textSize = 20f
            setPadding(0, 24, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "我是没有 @Route 的普通页面，只在被动态注册的时段内可路由（与静态路由同表共存）。"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })
        root.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        })

        Ui.applyEdgeInsets(root, this, extraTopDp = 20)
        setContentView(root)

        if (savedInstanceState == null && path != null) {
            Toast.makeText(
                this,
                "✓ TRouter 已打开动态路由本页：$path\n（运行时注册，未标 @Route）",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
