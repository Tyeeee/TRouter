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
import com.trouter.core.api.DemoParams
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
        fun line(text: String, size: Float = 14f, color: Int = Ui.COLOR_TEXT_GRAY) =
            Ui.lineText(requireContext(), text, size, color)


        val args = arguments
        val path = args?.getString(RouteLaunch.EXTRA_PATH)
        val runtime = RouteLaunch.describe(args)

        val d = Ui.dp(requireActivity(), 1)
        val view = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24 * d, 12 * d, 24 * d, 16 * d)
            addView(line("场景 S02 · 打开一个「页面片段」（Fragment）", 15f, Ui.COLOR_TITLE_BLUE))
            addView(line("路径 /fragment-demo · group=default · kind=FRAGMENT"))
            addView(line("本页片段由框架内置的容器页面装着，你不需要自己写容器"))
            addView(line("期望：跳转返回「成功」，类型是 Fragment"))
            addView(line(runtime, 14f, Ui.COLOR_EVIDENCE_GREEN))
            // 参数透传证据（Fragment）：调用方 bundle 经容器克隆为 arguments 送达
            val msg = args?.getString(DemoParams.KEY_MSG)
            if (msg != null) {
                val count = args?.getInt(DemoParams.KEY_COUNT, -1) ?: -1
                addView(line("参数透传 ✓ msg=$msg · count=$count", 14f, Ui.COLOR_EVIDENCE_GREEN))
            }
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
