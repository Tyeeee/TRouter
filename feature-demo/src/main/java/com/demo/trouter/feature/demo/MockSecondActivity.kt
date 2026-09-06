package com.demo.trouter.feature.demo
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
import com.trouter.core.api.DemoParams
import com.trouter.core.api.Ui

/**
 * S09 Mock 目标页（@Route /mock/second · mock group · ACTIVITY）。
 *
 * 本页不是业务页，而是「/second 的替身」：只有 Mock 拦截器开启时，
 * 点击 S01（导航 /second）才会被 Redirect 到这里（真实页不出现）。
 */
@Route(path = RouterContract.PATH_MOCK_SECOND, group = RouterContract.GROUP_MOCK)
class MockSecondActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fun line(text: String, size: Float = 14f, color: Int = Ui.COLOR_TEXT_GRAY) =
            Ui.lineText(this, text, size, color)


        val d = Ui.dp(this, 1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24 * d, 0, 24 * d, 24 * d)
        }

        // 运行时证据：本页确由 TRouter 打开（S09 Redirect 的可见反馈）
        val path = intent.getStringExtra(RouteLaunch.EXTRA_PATH)
        val runtime = RouteLaunch.describe(intent)

        root.addView(line("场景 S09 · Mock 目标页（MockInterceptor Redirect）", 15f, Ui.COLOR_TITLE_BLUE))
        root.addView(line("路径 /mock/second · group=mock · kind=ACTIVITY（/second 的替身）"))
        root.addView(line("期望：开启 Mock 开关后点 S01，本页替代真实 Second 页被打开（Redirect 生效）"))
        root.addView(line(runtime, 14f, Ui.COLOR_EVIDENCE_GREEN))
        root.addView(TextView(this).apply {
            text = "Mock Second 页面"
            textSize = 20f
            setPadding(0, 24, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "我是 /second 的 Mock 页。只有 Mock 拦截器开启时 /second 才会被重定向到这里；" +
                "关闭开关再点 S01 将回到真实 Second 页。"
            textSize = 14f
            setPadding(0, 0, 0, 16)
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
                "✓ Mock 页已打开：$path（经拦截器 Redirect，原 /second）\ntraceId=${intent.getStringExtra(RouteLaunch.EXTRA_TRACE_ID)}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
