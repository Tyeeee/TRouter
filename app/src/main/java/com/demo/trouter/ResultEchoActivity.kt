package com.demo.trouter

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.trouter.annotation.Route
import com.trouter.core.api.RouterContract
import com.trouter.core.api.RouteLaunch
import com.trouter.core.api.Ui

/** 结果键（演示用，单一来源）。 */
object ResultDemoKeys {
    const val EXTRA_RESULT_TEXT: String = "demo.result.text"
}

/**
 * 拿页面返回值 结果回调演示页（@Route /result-demo）：点击「返回并携带结果」把数据经
 * setResult 回传发起方（MainActivity 的 onActivityResult 接收并回显）。
 */
@Route(path = RouterContract.PATH_RESULT_DEMO)
class ResultEchoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fun line(text: String, size: Float = 14f, color: Int = Ui.COLOR_TEXT_GRAY) =
            Ui.lineText(this, text, size, color)

        val d = Ui.dp(this, 1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24 * d, 0, 24 * d, 24 * d)
        }

        root.addView(line("G3 · navigateForResult 结果回传", 15f, Ui.COLOR_TITLE_BLUE))
        root.addView(line("路径 /result-demo · 由 navigateForResult(requestCode) 打开"))
        root.addView(line(RouteLaunch.describe(intent), 14f, Ui.COLOR_EVIDENCE_GREEN))
        root.addView(Button(this).apply {
            text = "返回并携带结果"
            setOnClickListener {
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(ResultDemoKeys.EXTRA_RESULT_TEXT, "来自 Result 页的返回数据"),
                )
                finish()
            }
        })
        root.addView(Button(this).apply {
            text = "直接返回（无结果）"
            setOnClickListener { finish() }
        })

        Ui.applyEdgeInsets(root, this, extraTopDp = 20)
        setContentView(root)
    }
}
