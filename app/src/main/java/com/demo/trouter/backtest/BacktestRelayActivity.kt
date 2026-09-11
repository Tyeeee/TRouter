package com.demo.trouter.backtest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.demo.trouter.R
import com.trouter.annotation.Route
import com.trouter.core.api.RouteArgs
import com.trouter.core.api.RouteLaunch
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult
import com.trouter.core.api.Ui

/**
 * 中继操作页（`/bt-relay`）——回测用「模拟实际操作」的页面之二。
 *
 * 它模拟的是真实业务里最常见的一种形态：**页面 A 里再打开页面 B**（而且 A 还想知道 B 打没打开）。
 * 回测因此能验到单点上验不到的东西：多跳导航的返回栈是否还成立、
 * "由页面发起的跳转"结果是否准确、"B 返回后 A 是否还在前台"。
 *
 * 入参（由发起方经路由 bundle 传入）：
 * - [BacktestContract.KEY_RELAY_TARGET]：本页要打开的下一跳 path；
 * - [BacktestContract.KEY_RELAY_ASYNC]：true = 用异步方式打开（顺带验证"页面内用异步跳转"）。
 */
@Route(path = BacktestContract.PATH_RELAY)
class BacktestRelayActivity : ComponentActivity() {

    private lateinit var resultView: TextView
    private var nestedResult: TRouterResult? = null
    private var targetPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = Ui.dp(this, 1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24 * d, 0, 24 * d, 24 * d)
        }
        val args = RouteArgs.of(intent)
        targetPath = args.str(BacktestContract.KEY_RELAY_TARGET) ?: ""
        val async = args.boolean(BacktestContract.KEY_RELAY_ASYNC, false)

        root.addView(Ui.lineText(this, "回测中继页 · 在页面里再打开一个页面", 15f, Ui.COLOR_TITLE_BLUE))
        root.addView(Ui.lineText(this, RouteLaunch.describe(intent), 14f, Ui.COLOR_EVIDENCE_GREEN))
        root.addView(Ui.lineText(this, "本页要打开：$targetPath（async=$async）", 14f, Ui.COLOR_TEXT_GRAY))

        resultView = Ui.lineText(this, "（中继跳转结果：待发起）", 14f, Ui.COLOR_TEXT_GRAY)
        resultView.id = R.id.relay_result
        root.addView(resultView)
        root.addView(TextView(this).apply {
            id = R.id.relay_target
            text = targetPath
            visibility = android.view.View.GONE
        })

        root.addView(Button(this).apply {
            id = R.id.relay_return
            text = "返回并回传中继证据"
            setOnClickListener { returnWithEvidence() }
        })
        root.addView(Button(this).apply {
            text = "直接返回"
            setOnClickListener { finish() }
        })

        Ui.applyEdgeInsets(root, this, extraTopDp = 20)
        setContentView(root)

        if (savedInstanceState == null && targetPath.isNotEmpty()) {
            if (async) {
                TRouter.navigateAsync(targetPath) { record(it) }
            } else {
                record(TRouter.navigate(targetPath))
            }
        }
    }

    private fun record(result: TRouterResult) {
        nestedResult = result
        resultView.text = "中继跳转结果：${describe(result)}"
    }

    /** 中继页真实发起的那一跳的结果（节点据此断言）。 */
    fun nestedResultText(): String = describe(nestedResult)

    /** 模拟用户点"返回并回传中继证据"：把本页的中继证据 setResult 回发起方。 */
    fun returnWithEvidence() {
        val payload = "relay→$targetPath 结果=${nestedResultText()}"
        setResult(Activity.RESULT_OK, Intent().putExtra(BacktestContract.KEY_RELAY_RESULT, payload))
        finish()
    }

    private fun describe(result: TRouterResult?): String = when (result) {
        null -> "（没有发起）"
        is TRouterResult.Success -> "成功 ${result.meta.path}"
        is TRouterResult.NotFound -> "未找到 ${result.path}"
        is TRouterResult.Blocked -> "被拦下 ${result.path}（${result.reason}）"
        TRouterResult.NotInitialized -> "未初始化"
    }
}
