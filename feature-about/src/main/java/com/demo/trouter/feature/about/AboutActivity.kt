package com.demo.trouter.feature.about
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.trouter.annotation.Route
import com.trouter.core.api.RouterContract
import com.trouter.core.api.RouteLaunch
import com.trouter.core.api.Ui

/**
 * S03 目标页（@Route /about · secondary · ACTIVITY）。
 * 验证非默认分组的路由声明/加载器生成/导航；横幅含运行时证据。
 */
@Route(path = RouterContract.PATH_ABOUT, group = "secondary")
class AboutActivity : ComponentActivity() {

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
            "✓ 本次由 TRouter 打开 · path=$path · kind=${intent.getStringExtra(RouteLaunch.EXTRA_KIND)} · " +
                "group=${intent.getStringExtra(RouteLaunch.EXTRA_GROUP)} · " +
                "traceId=${intent.getStringExtra(RouteLaunch.EXTRA_TRACE_ID)} · " +
                "解析 ${intent.getLongExtra(RouteLaunch.EXTRA_COST_MS, -1)}ms"
        } else {
            "（直连/系统启动：本页未带 TRouter 路由元数据）"
        }

        root.addView(line("场景 S03 · 分组路由（secondary group）", 15f, Color.rgb(0, 102, 204)))
        root.addView(line("路径 /about · group=secondary · kind=ACTIVITY"))
        root.addView(line("期望：页面展示；日志出现 [GroupLoader][load][start/end] group=secondary"))
        root.addView(line(runtime, 14f, Color.rgb(27, 127, 59)))
        root.addView(TextView(this).apply {
            text = "About 页面（secondary group）"
            textSize = 20f
            setPadding(0, 24, 0, 8)
        })
        root.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        })

        Ui.applyEdgeInsets(root, this, extraTopDp = 20)
        setContentView(root)

        // 入口即时反馈：Toast 弹出本次路由结果
        if (savedInstanceState == null && path != null) {
            Toast.makeText(
                this,
                "✓ TRouter 已打开本页：$path（${intent.getStringExtra(RouteLaunch.EXTRA_KIND)}）\ntraceId=${intent.getStringExtra(RouteLaunch.EXTRA_TRACE_ID)}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
