package com.demo.trouter.backtest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.trouter.core.api.DemoParams
import com.demo.trouter.R
import com.trouter.annotation.Route
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult
import com.trouter.core.api.Ui

/**
 * 回测台主页（`/backtest`）——「多个测试节点 + 页面模拟实际操作」的入口。
 *
 * 用法：
 * - 点「开始回测」：按功能点顺序逐条跑节点，每行实时显示通过/失败与一句话结论；
 * - 每个节点旁边也有单独的「跑」按钮，便于只复验某一条；
 * - 底部日志区显示正在做什么，失败时把原因直接摊开；
 * - 报告同时写进 `filesDir/backtest/last-report.txt` 与 logcat（Tag=TRouterBacktest），
 *   自动化用例与人工复核读的是同一份结论。
 *
 * 另外它自己也是一个**可被路由打开的页面**，并且提供了两个"手动操作"入口
 * （打开参数表单页 / 打开 /second），用来对照自动回测的结果。
 */
@Route(path = BacktestContract.PATH_CONSOLE)
class BacktestConsoleActivity : ComponentActivity(), BacktestHost {

    private lateinit var summaryText: TextView
    private lateinit var logText: TextView
    private lateinit var runAllButton: Button
    private val statusViews = LinkedHashMap<String, TextView>()
    private val runButtons = LinkedHashMap<String, Button>()
    private val logLines = ArrayList<String>()

    private val pendingResults = HashMap<Int, (resultCode: Int, data: Intent?) -> Unit>()

    // 现代 Activity Result API：在构造期注册（这是它的要求），回测节点用它来验证 buildIntent 的产物
    private var pendingModernResult: ((resultCode: Int, data: Intent?) -> Unit)? = null

    private val modernLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingModernResult
            pendingModernResult = null
            callback?.invoke(result.resultCode, result.data)
        }

    override val hostActivity: Activity get() = this
    override val hostResumed: Boolean get() = resumed

    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 页面自己保证"路由是可用的"：同一进程里可能刚跑过会重置 TRouter 的用例，
        // 那种情况下不补装配，点任何按钮都会"没反应"（看起来像库坏了，其实是环境没摆正）
        BacktestStation.ensureWiring(application) { line -> android.util.Log.i("TRouterBacktest", line) }

        val d = Ui.dp(this, 1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16 * d, 0, 16 * d, 16 * d)
        }
        val fullWidth = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        root.addView(TextView(this).apply {
            text = "TRouter 回测台"
            textSize = 22f
        }, fullWidth)
        root.addView(Ui.lineText(this, "本页把每个功能点做成一个节点：真的发起跳转、真的打开页面、真的跨进程调用，" +
            "再对照「屏幕上究竟发生了什么」给结论。报告同时写到应用私有目录与 logcat（TRouterBacktest）。", 13f))
        root.addView(Ui.lineText(this, "路径 ${BacktestContract.PATH_CONSOLE} · 共 ${BacktestStation.allNodes().size} 个节点", 13f))

        summaryText = TextView(this).apply {
            id = R.id.backtest_summary
            text = "尚未运行"
            textSize = 16f
            setPadding(0, 12, 0, 12)
        }
        root.addView(summaryText, fullWidth)

        runAllButton = Button(this).apply {
            id = R.id.backtest_run_all
            text = "开始回测（全部 ${BacktestStation.allNodes().size} 个节点）"
            setOnClickListener {
                BacktestStation.run(this@BacktestConsoleActivity) { report ->
                    summaryText.text = "${report.total} 个节点：通过 ${report.passCount}，失败 ${report.failCount}，" +
                        "跳过 ${report.skipCount}，耗时 ${report.durationMs}ms"
                }
            }
        }
        root.addView(runAllButton, fullWidth)

        root.addView(Button(this).apply {
            id = R.id.backtest_open_form
            text = "手动：打开参数操作页（${BacktestContract.PATH_FORM}）"
            setOnClickListener {
                val bundle = Bundle().apply {
                    putString(DemoParams.KEY_MSG, "回测台手动传参")
                    putInt(DemoParams.KEY_COUNT, 5)
                }
                navigateAndToast(BacktestContract.PATH_FORM, bundle)
            }
        }, fullWidth)
        root.addView(Button(this).apply {
            id = R.id.backtest_open_second
            text = "手动：打开 ${RouterContract.PATH_SECOND}"
            setOnClickListener { navigateAndToast(RouterContract.PATH_SECOND, null) }
        }, fullWidth)

        // ---- 节点清单
        for ((feature, nodes) in BacktestStation.nodesByFeature()) {
            root.addView(TextView(this).apply {
                text = feature
                textSize = 15f
                setTextColor(Ui.COLOR_TITLE_BLUE)
                setPadding(0, 20, 0, 4)
            }, fullWidth)
            for (node in nodes) {
                root.addView(nodeRow(node), fullWidth)
            }
        }

        logText = TextView(this).apply {
            id = R.id.backtest_log
            text = "日志：等待开始"
            textSize = 12f
            setTextColor(Ui.COLOR_TEXT_GRAY)
            setPadding(0, 20, 0, 8)
        }
        root.addView(logText, fullWidth)

        val scroll = ScrollView(this).apply {
            id = R.id.backtest_scroll
            addView(root)
        }
        Ui.applyEdgeInsets(scroll, this, extraTopDp = 16, extraBottomDp = 8)
        setContentView(scroll)

        // 进页面就把上一轮的报告贴出来（复核不用重新跑）
        BacktestStation.lastReport?.let { last ->
            summaryText.text = "上一轮：${last.total} 个节点，通过 ${last.passCount}，失败 ${last.failCount}"
            last.results.forEach { r -> applyResult(r) }
        }
    }

    /** 一行节点：状态 + 标题 + 单独的「跑」按钮。 */
    private fun nodeRow(node: BacktestNode): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 10)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = TextView(this).apply {
            text = "待运行"
            textSize = 14f
            width = Ui.dp(this@BacktestConsoleActivity, 62)
            tag = statusTag(node.id)
            setTextColor(Ui.COLOR_TEXT_GRAY)
        }
        statusViews[node.id] = status
        head.addView(status)
        head.addView(TextView(this).apply {
            text = "${node.id} ${node.title}"
            textSize = 14f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val runButton = Button(this).apply {
            text = "跑"
            textSize = 12f
            tag = runTag(node.id)
            setOnClickListener {
                BacktestStation.run(this@BacktestConsoleActivity, only = listOf(node.id))
            }
        }
        runButtons[node.id] = runButton
        head.addView(runButton)
        row.addView(head)
        row.addView(TextView(this).apply {
            text = "期望：${node.expected}"
            textSize = 12f
            setTextColor(Ui.COLOR_TEXT_GRAY)
        })
        return row
    }

    private fun navigateAndToast(path: String, bundle: Bundle?) {
        when (val result = TRouter.navigate(path, bundle)) {
            is TRouterResult.Success -> onLog("手动操作：已打开 ${result.meta.path}")
            is TRouterResult.NotFound -> onLog("手动操作：未找到 $path")
            is TRouterResult.Blocked -> onLog("手动操作：被拦下 ${result.reason}")
            TRouterResult.NotInitialized -> onLog("手动操作：TRouter 未初始化")
        }
    }

    // ------------------------------------------------------------------ BacktestHost

    override fun launchForResult(
        path: String,
        requestCode: Int,
        bundle: Bundle?,
        onResult: (resultCode: Int, data: Intent?) -> Unit,
    ): TRouterResult {
        pendingResults[requestCode] = onResult
        return TRouter.navigateForResult(path, requestCode, bundle)
    }

    override fun launchWithActivityResult(
        intent: Intent,
        onResult: (resultCode: Int, data: Intent?) -> Unit,
    ) {
        // 注意：这里**没有** requestCode —— 结果只走 registerForActivityResult 的注册回调
        pendingModernResult = onResult
        modernLauncher.launch(intent)
    }

    override fun onNodeStart(node: BacktestNode, index: Int, total: Int) {
        runOnUiThread {
            statusViews[node.id]?.let {
                it.text = "运行中"
                it.setTextColor(Ui.COLOR_TITLE_BLUE)
            }
            summaryText.text = "运行中 ${index + 1}/$total …（当前：${node.id}）"
            runAllButton.isEnabled = false
        }
    }

    override fun onNodeFinished(result: NodeResult) {
        runOnUiThread { applyResult(result) }
    }

    private fun applyResult(result: NodeResult) {
        val view = statusViews[result.id] ?: return
        when (result.status) {
            NodeStatus.PASS -> {
                view.text = "通过"
                view.setTextColor(Ui.COLOR_EVIDENCE_GREEN)
            }
            NodeStatus.FAIL -> {
                view.text = "失败"
                view.setTextColor(Ui.COLOR_ERROR_RED)
            }
            NodeStatus.SKIP -> {
                view.text = "跳过"
                view.setTextColor(Ui.COLOR_TEXT_GRAY)
            }
        }
        view.contentDescription = "${result.id}:${result.status.name}:${result.detail}"
    }

    override fun onReport(report: BacktestReport) {
        runOnUiThread {
            runAllButton.isEnabled = true
            summaryText.text = "${report.total} 个节点：通过 ${report.passCount}，失败 ${report.failCount}，" +
                "跳过 ${report.skipCount}，耗时 ${report.durationMs}ms"
            if (report.failCount > 0) {
                summaryText.setTextColor(Ui.COLOR_ERROR_RED)
                onLog("失败节点：${report.failures.joinToString("；") { "${it.id}(${it.detail})" }}")
            } else {
                summaryText.setTextColor(Ui.COLOR_EVIDENCE_GREEN)
            }
        }
    }

    override fun onLog(line: String) {
        runOnUiThread {
            logLines += line
            while (logLines.size > 40) logLines.removeAt(0)
            if (::logText.isInitialized) logText.text = logLines.joinToString("\n")
        }
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        pendingResults.remove(requestCode)?.invoke(resultCode, data)
    }

    companion object {
        /** Espresso 用：按 tag 找到某个节点的状态行 / 单独运行按钮。 */
        fun statusTag(nodeId: String): String = "bt-status-$nodeId"
        fun runTag(nodeId: String): String = "bt-run-$nodeId"
    }
}
