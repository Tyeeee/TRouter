package com.demo.trouter.feature.demo
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.trouter.annotation.Route
import com.trouter.core.api.RouterContract
import com.trouter.core.api.RouteLaunch
import com.trouter.core.api.Ui

/**
 * S02 目标页（@Route /fragment-demo · default · FRAGMENT）。
 * 由 core 内置 FragmentContainerActivity 承载；横幅含运行时证据（读 Fragment arguments）。
 */
@Route(path = RouterContract.PATH_FRAGMENT_DEMO)
class DemoFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        fun line(text: String, size: Float = 14f, color: Int = Color.rgb(102, 102, 102)) =
            TextView(requireContext()).apply {
                this.text = text
                this.textSize = size
                setTextColor(color)
                setPadding(0, 4, 0, 4)
            }

        val args = arguments
        val path = args?.getString(RouteLaunch.EXTRA_PATH)
        val runtime = if (path != null) {
            "✓ 本次由 TRouter 打开 · path=$path · kind=${args.getString(RouteLaunch.EXTRA_KIND)} · " +
                "group=${args.getString(RouteLaunch.EXTRA_GROUP)} · " +
                "traceId=${args.getString(RouteLaunch.EXTRA_TRACE_ID)} · " +
                "解析 ${args.getLong(RouteLaunch.EXTRA_COST_MS, -1)}ms"
        } else {
            "（直连/系统启动：本页未带 TRouter 路由元数据）"
        }

        val d = Ui.dp(requireActivity(), 1)
        val view = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24 * d, 12 * d, 24 * d, 16 * d)
            addView(line("场景 S02 · Fragment 目标承载", 15f, Color.rgb(0, 102, 204)))
            addView(line("路径 /fragment-demo · group=default · kind=FRAGMENT"))
            addView(line("承载：core 内置 FragmentContainerActivity（系统栏已避让）"))
            addView(line("期望：navigate 返回 Success(kind=FRAGMENT)"))
            addView(line(runtime, 14f, Color.rgb(27, 127, 59)))
            addView(TextView(requireContext()).apply {
                text = "Fragment 演示页面"
                textSize = 20f
                setPadding(0, 20, 0, 8)
            })
            addView(Button(requireContext()).apply {
                text = "返回"
                setOnClickListener { requireActivity().finish() }
            })
        }

        // 入口即时反馈：Toast 弹出本次路由结果
        if (path != null) {
            Toast.makeText(
                requireContext(),
                "✓ TRouter 已打开本页：$path（${args?.getString(RouteLaunch.EXTRA_KIND)}）\ntraceId=${args?.getString(RouteLaunch.EXTRA_TRACE_ID)}",
                Toast.LENGTH_LONG,
            ).show()
        }
        return view
    }
}
