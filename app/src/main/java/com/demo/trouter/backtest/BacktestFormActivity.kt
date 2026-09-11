package com.demo.trouter.backtest

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.demo.trouter.DemoInterceptors
import com.demo.trouter.R
import com.demo.trouter.TRouterDemoApp
import com.trouter.annotation.Route
import com.trouter.core.api.DemoParams
import com.trouter.core.api.RouteArgs
import com.trouter.core.api.RouteLaunch
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult
import com.trouter.core.api.Ui

/**
 * 参数操作页（`/bt-form`）——回测用「模拟实际操作」的页面之一。
 *
 * 它存在的意义：前面那些节点都是"回测台自己发起跳转"。但真实业务里，
 * **发起跳转的是某个业务页面**，参数也来自用户在页面上的输入。
 * 本页因此提供真实可输入的输入框 + 真实可点的按钮：
 * - 人可以直接点着玩；
 * - 自动化用例用 Espresso 真的输入文字、真的点按钮（见 `BacktestOperationUiTest`）；
 * - 回测节点则调用与按钮**完全同一份**的处理逻辑（[clickSync] 等就是按钮的点击实现），
 *   从而在不依赖 UI 自动化的情况下也走同一条真实路径。
 */
@Route(path = BacktestContract.PATH_FORM)
class BacktestFormActivity : ComponentActivity() {

    private lateinit var evidenceText: TextView
    private lateinit var resultText: TextView
    private lateinit var msgInput: EditText
    private lateinit var countInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = Ui.dp(this, 1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24 * d, 0, 24 * d, 24 * d)
        }

        root.addView(Ui.lineText(this, "回测操作页 · 在页面里真实输入参数并真实点击跳转", 15f, Ui.COLOR_TITLE_BLUE))
        root.addView(Ui.lineText(this, "路径 ${BacktestContract.PATH_FORM} · kind=ACTIVITY · group=default"))

        // 页面自述：本页是不是被路由打开的、收到了什么参数（与业务页一致的那套证据）
        val args = RouteArgs.of(intent)
        evidenceText = Ui.lineText(this, "", 14f, Ui.COLOR_EVIDENCE_GREEN)
        evidenceText.id = R.id.form_evidence
        root.addView(evidenceText)
        val msg = args.str(DemoParams.KEY_MSG)
        evidenceText.text = buildString {
            append(RouteLaunch.describe(intent))
            if (msg != null) {
                append("\n参数透传 ✓ msg=$msg · count=${args.int(DemoParams.KEY_COUNT, -1)}")
            }
        }

        root.addView(Ui.lineText(this, "参数（输入框里的内容会被真的带给目标页）", 14f, Ui.COLOR_TEXT_GRAY))
        msgInput = EditText(this).apply {
            id = R.id.form_msg
            hint = "要传的文本"
            setText(msg ?: "来自表单页的参数")
        }
        root.addView(msgInput)
        countInput = EditText(this).apply {
            id = R.id.form_count
            hint = "要传的数字"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(args.int(DemoParams.KEY_COUNT, 7).toString())
        }
        root.addView(countInput)

        fun addButton(id: Int, label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                this.id = id
                text = label
                setOnClickListener { action() }
            })
        }

        addButton(R.id.form_go_sync, "同步跳转 ${RouterContract.PATH_SECOND}") { clickSync() }
        addButton(R.id.form_go_async, "异步跳转 ${RouterContract.PATH_SECOND}") { clickAsync { } }
        addButton(R.id.form_go_remote, "跨进程跳转 ${RouterContract.PATH_REMOTE_THIRD}（:remote2）") { clickRemote { } }
        addButton(R.id.form_go_notfound, "打开不存在的路径") { clickNotFound() }

        resultText = Ui.lineText(this, "（还没发起过跳转）", 14f, Ui.COLOR_TEXT_GRAY)
        resultText.id = R.id.form_result
        root.addView(resultText)

        root.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        })

        Ui.applyEdgeInsets(root, this, extraTopDp = 20)
        setContentView(root)
    }

    // ------------------------------------------------------------------ 供人点、供自动化点、供回测节点调用的同一份实现

    fun setInputsForTest(msg: String, count: Int) {
        msgInput.setText(msg)
        countInput.setText(count.toString())
    }

    /** 页面上真实可见的结果行文本（断言"用户看到的"而不是"内部变量"）。 */
    fun resultLine(): String = resultText.text.toString()

    fun evidenceLine(): String = evidenceText.text.toString()

    private fun inputBundle(): Bundle = Bundle().apply {
        putString(DemoParams.KEY_MSG, msgInput.text.toString())
        putInt(DemoParams.KEY_COUNT, countInput.text.toString().toIntOrNull() ?: -1)
    }

    /** 同步跳转：按钮与节点共用。 */
    fun clickSync(): TRouterResult {
        val result = TRouter.navigate(RouterContract.PATH_SECOND, inputBundle())
        show(result)
        return result
    }

    /** 异步跳转：按钮与节点共用（异步回调里同样把结果显示在页面上）。 */
    fun clickAsync(onResult: (TRouterResult) -> Unit) {
        resultText.text = "已发起异步跳转，等待结果…"
        TRouter.navigateAsync(RouterContract.PATH_SECOND, inputBundle()) { result ->
            show(result)
            onResult(result)
        }
    }

    /** 跨进程跳转：按钮与节点共用。 */
    fun clickRemote(onResult: (TRouterResult) -> Unit) {
        resultText.text = "已发起跨进程跳转，等待结果…"
        TRouter.navigateRemote(
            RouterContract.PATH_REMOTE_THIRD,
            inputBundle(),
            TRouterDemoApp.REMOTE_TARGET_SECOND,
        ) { result ->
            show(result)
            onResult(result)
        }
    }

    /** 打开一个刻意没注册的路径：页面上应当出现"未找到"，而不是崩溃。 */
    fun clickNotFound(): TRouterResult {
        val result = TRouter.navigate(RouterContract.PATH_UNREGISTERED, inputBundle())
        show(result)
        return result
    }

    private fun show(result: TRouterResult) {
        val text = when (result) {
            is TRouterResult.Success -> "跳转成功 ✓ ${result.meta.path}（${result.meta.kind}）"
            is TRouterResult.NotFound -> "未找到路径：${result.path}"
            is TRouterResult.Blocked -> "被拦下：${result.path}（${result.reason}）"
            TRouterResult.NotInitialized -> "TRouter 未初始化"
        }
        resultText.text = text
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /** 让节点能确认"异步拦截器开关"这类全局状态没有被页面偷偷改动。 */
    fun asyncInterceptorEnabled(): Boolean = DemoInterceptors.async.enabled

    companion object {
        /** 结果码：本页被用来验证"页面返回数据"时使用。 */
        const val RESULT_OK_TEXT: String = "form-page-result"
    }
}

/** 供节点判断"这个 Activity 是不是表单页"（避免节点到处 import 页面类）。 */
fun Activity.asBacktestForm(): BacktestFormActivity? = this as? BacktestFormActivity
