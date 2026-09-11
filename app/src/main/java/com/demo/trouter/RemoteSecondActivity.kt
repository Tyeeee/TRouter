package com.demo.trouter

import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.trouter.annotation.CrossProcess
import com.trouter.annotation.Interceptor
import com.trouter.annotation.Route
import com.trouter.core.api.RouterContract
import com.trouter.core.api.RouteLaunch
import com.trouter.core.api.DemoParams
import com.trouter.core.api.Ui

/**
 * S11 跨进程目标页（@Route /remote-second · default · ACTIVITY + @CrossProcess + @Interceptor(remoteAudit)）。
 *
 * 本页在 manifest 中声明到独立进程 android:process=":remote"：
 * - 经 host 的 TRouter.navigateRemote（AIDL 通道）由 **:remote 进程自己的 TRouter** 打开；
 * - 横幅运行时证据来自远端 TRouter.openTarget 写入的 RouteLaunch 元数据（远端 traceId）；
 * - @Interceptor(remoteAudit)：目标级拦截器演示（只给某个页面挂拦截器），真实 app 绑定 no-op 观察者，测试可替换。
 */
@Route(path = RouterContract.PATH_REMOTE_SECOND)
@CrossProcess
@Interceptor(names = ["remoteAudit"])
class RemoteSecondActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fun line(text: String, size: Float = 14f, color: Int = Ui.COLOR_TEXT_GRAY) =
            Ui.lineText(this, text, size, color)


        val d = Ui.dp(this, 1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24 * d, 0, 24 * d, 24 * d)
        }

        val path = intent.getStringExtra(RouteLaunch.EXTRA_PATH)
        val runtime = RouteLaunch.describe(intent)

        root.addView(line("场景 S11 · 跨进程导航（AIDL → :remote 进程）", 15f, Ui.COLOR_TITLE_BLUE))
        root.addView(line("路径 /remote-second · group=default · kind=ACTIVITY · @CrossProcess"))
        root.addView(line("本页运行在独立进程 :remote · 进程号 ${Process.myPid()}（与主进程的进程号不同，说明确实跨进程了）", 14f, Ui.COLOR_REMOTE_PURPLE))
        root.addView(line(runtime, 14f, Ui.COLOR_EVIDENCE_GREEN))
        // 参数透传证据（跨进程）：调用方 bundle 经 AIDL 到达 :remote 进程的 intent extras
        val msg = intent.getStringExtra(DemoParams.KEY_MSG)
        if (msg != null) {
            val count = intent.getIntExtra(DemoParams.KEY_COUNT, -1)
            root.addView(line("参数透传 ✓ msg=$msg · count=$count", 14f, Ui.COLOR_EVIDENCE_GREEN))
        }
        root.addView(TextView(this).apply {
            text = "Remote Second 页面（第二进程）"
            textSize = 20f
            setPadding(0, 24, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "本页是这样打开的：在主进程点「S11 跨进程导航」后，主进程把「要打开哪个页面」" +
                "发给 :remote 进程，由这边的 TRouter 用自己的路径表打开本页，再把结果回传给主进程。"
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
                "✓ remote 进程已打开本页：$path\npid=${Process.myPid()}（:remote）",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
