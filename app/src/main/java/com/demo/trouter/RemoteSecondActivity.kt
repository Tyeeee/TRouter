package com.demo.trouter

import android.content.Context
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
 * - @Interceptor(remoteAudit)：目标级拦截器演示（L3），真实 app 绑定 no-op 观察者，测试可替换。
 */
@Route(path = RouterContract.PATH_REMOTE_SECOND)
@CrossProcess
@Interceptor(names = ["remoteAudit"])
class RemoteSecondActivity : ComponentActivity() {

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
            "✓ 本次由 remote 进程 TRouter 打开 · path=$path · kind=${intent.getStringExtra(RouteLaunch.EXTRA_KIND)} · " +
                "traceId=${intent.getStringExtra(RouteLaunch.EXTRA_TRACE_ID)}"
        } else {
            "（直连/系统启动：本页未带 TRouter 路由元数据）"
        }

        root.addView(line("场景 S11 · 跨进程导航（AIDL → :remote 进程）", 15f, Color.rgb(0, 102, 204)))
        root.addView(line("路径 /remote-second · group=default · kind=ACTIVITY · @CrossProcess"))
        root.addView(line("本页运行在独立进程 :remote · pid=${Process.myPid()}（与 host 进程 pid 不同即证明跨进程）", 14f, Color.rgb(128, 0, 128)))
        root.addView(line(runtime, 14f, Color.rgb(27, 127, 59)))
        // 参数透传证据（跨进程）：调用方 bundle 经 AIDL 到达 :remote 进程的 intent extras
        val msg = intent.getStringExtra(DemoParams.KEY_MSG)
        if (msg != null) {
            val count = intent.getIntExtra(DemoParams.KEY_COUNT, -1)
            root.addView(line("参数透传 ✓ msg=$msg · count=$count", 14f, Color.rgb(27, 127, 59)))
        }
        // 跨进程参数回读（host 测试侧 SharedPreferences 校验用，同文件跨进程共享；MULTI_PROCESS 强制磁盘重载）
        runCatching {
            @Suppress("DEPRECATION")
            applicationContext.getSharedPreferences(DemoParams.PREF_NAME, Context.MODE_MULTI_PROCESS)
                .edit()
                .putString(DemoParams.PREF_LAST_PATH, path ?: "")
                .putString(DemoParams.PREF_LAST_MSG, msg ?: "-")
                .putInt(DemoParams.PREF_LAST_COUNT, intent.getIntExtra(DemoParams.KEY_COUNT, -1))
                .commit()
        }
        root.addView(TextView(this).apply {
            text = "Remote Second 页面（第二进程）"
            textSize = 20f
            setPadding(0, 24, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "本页由 host 点击「S11 跨进程导航」后，导航请求经 AIDL 发往 :remote 进程，" +
                "由该进程的 TRouter 实例解析并打开（结果异步回传 host）。"
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
