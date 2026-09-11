package com.demo.trouter

import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.trouter.annotation.CrossProcess
import com.trouter.annotation.Route
import com.trouter.core.api.DemoParams
import com.trouter.core.api.RouteArgs
import com.trouter.core.api.RouteLaunch
import com.trouter.core.api.RouterContract
import com.trouter.core.api.Ui

/**
 * S26 跨进程目标页（多进程与跨进程增强）：`@Route` /remote-third + `@CrossProcess`，
 * manifest 声明在**第三个进程** `:remote2`（与 host、:remote 并列）。
 *
 * 由 host 经 `TRouter.navigateRemote(path, bundle, target = "remote2")` 打开，
 * 页面上显示所在进程名与 pid，用于肉眼确认"确实是第三个进程在跑"。
 */
@Route(path = RouterContract.PATH_REMOTE_THIRD)
@CrossProcess
class RemoteThirdActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fun line(text: String, size: Float = 14f, color: Int = Ui.COLOR_TEXT_GRAY) =
            Ui.lineText(this, text, size, color)

        val d = Ui.dp(this, 1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24 * d, 0, 24 * d, 24 * d)
        }

        val processName = DemoProcess.name(this)
        val args = RouteArgs.of(intent)
        val msg = args.str(DemoParams.KEY_MSG)

        root.addView(line("场景 S26 · 跨进程导航到第三个进程（:remote2）", 15f, Ui.COLOR_TITLE_BLUE))
        root.addView(line("路径 ${RouterContract.PATH_REMOTE_THIRD} · group=default · kind=ACTIVITY · @CrossProcess"))
        root.addView(
            line(
                "本页运行在进程 $processName · pid=${Process.myPid()}（与 host、:remote 都不同即证明三进程）",
                14f,
                Ui.COLOR_REMOTE_PURPLE,
            ),
        )
        root.addView(line(RouteLaunch.describe(intent), 14f, Ui.COLOR_EVIDENCE_GREEN))
        if (msg != null) {
            root.addView(
                line(
                    "参数透传 ✓ msg=$msg · count=${args.int(DemoParams.KEY_COUNT, -1)}",
                    14f,
                    Ui.COLOR_EVIDENCE_GREEN,
                ),
            )
        }
        root.addView(TextView(this).apply {
            text = "Remote Third 页面（第三进程 :remote2）"
            textSize = 20f
            setPadding(0, 24, 0, 8)
        })
        root.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        })

        Ui.applyEdgeInsets(root, this, extraTopDp = 20)
        setContentView(root)

        if (savedInstanceState == null) {
            Toast.makeText(this, "✓ $processName 已打开本页 · pid=${Process.myPid()}", Toast.LENGTH_LONG).show()
        }
    }
}
