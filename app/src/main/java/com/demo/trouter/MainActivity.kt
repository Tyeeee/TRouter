package com.demo.trouter

import android.app.Activity
import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.trouter.annotation.Route
import com.trouter.core.api.DemoParams
import com.trouter.core.api.RouteLaunch
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouterContract
import com.trouter.core.api.RouteTargetKind
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult
import com.demo.trouter.generated.TRouterPojo_DemoReport
import com.trouter.core.api.Ui

/**
 * TRouter V1.0 测试台 · 场景索引（主页，@Route /main）。
 *
 * 交互说明：
 * - 每个场景行（含行尾 › 与点击水波纹）可点击，进入对应验证页；
 * - 目标页内用「返回」按钮或系统返回键即可回到本页（同任务导航）；
 * - 底部「路由表快照」为只读展示，来自 TRouter.registeredRoutes()。
 */
@Route(path = RouterContract.PATH_MAIN)
class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var graphText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val d = (density + 0.5f).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 左右/底部基础间距用 dp；顶部交给 Ui.applyEdgeInsets 叠加状态栏
            setPadding(20 * d, 0, 20 * d, 12 * d)
        }
        val contentWidth = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        fun sectionTitle(title: String) {
            root.addView(TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(Ui.COLOR_TITLE_BLUE)
                setPadding(0, 24, 0, 8)
            }, contentWidth)
        }

        fun scenarioRow(id: Int, title: String, subtitle: String, action: () -> Unit) {
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 17f
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(Ui.COLOR_TEXT_GRAY)
                })
            }
            val chevron = TextView(this).apply {
                text = "›"
                textSize = 26f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.rgb(153, 153, 153))
            }
            val row = LinearLayout(this).apply {
                this.id = id
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                minimumHeight = (64 * density).toInt()
                setPadding(4, 8, 4, 8)
                addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(chevron)
                setOnClickListener { action() }
                // 点击水波纹：让用户一眼看出可点
                val ripple = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
            }
            root.addView(row, contentWidth)
        }

        fun infoLine(text: String, color: Int = Ui.COLOR_TEXT_GRAY) {
            root.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(color)
                setPadding(0, 4, 0, 4)
            }, contentWidth)
        }

        /** V3.0 多模块可见化：按目标类包名推导来源模块（纯 UI 展示，不改 core 数据模型）。 */
        fun moduleTagOf(className: String): String = when {
            className.contains(".feature.demo.") -> "feature-demo"
            className.contains(".feature.about.") -> "feature-about"
            else -> "host"
        }

        root.addView(TextView(this).apply {
            text = "TRouter 测试台 · V1.0 导航 + V2.0 拦截器"
            textSize = 22f
        })
        infoLine(
            "点击下方带 › 的场景行即可进入对应验证页（行内水波纹提示可点）；" +
                "目标页可用「返回」按钮或系统返回键回到本页。",
        )

        sectionTitle("A · 导航成功（Activity / Fragment）")
        scenarioRow(R.id.scenario_s01, "S01 基础页面跳转（Activity）", "路径 /second · default · 期望：Second 页展示，navigate 返回 Success") {
            open(RouterContract.PATH_SECOND)
        }
        scenarioRow(R.id.scenario_s02, "S02 Fragment 目标承载", "路径 /fragment-demo · default · 期望：Fragment 演示页经 core 容器展示，Success(kind=FRAGMENT)") {
            open(RouterContract.PATH_FRAGMENT_DEMO)
        }
        infoLine("（说明）S06 主页自身 · /main · default · ACTIVITY —— 本页即通过 @Route 注册的可路由页面，无需点击。")

        sectionTitle("B · 分组路由（secondary group）")
        scenarioRow(R.id.scenario_s03, "S03 分组路由 · About 页", "路径 /about · group=secondary · 期望：页面展示，日志出现 group=secondary 加载起止") {
            open(RouterContract.PATH_ABOUT)
        }

        sectionTitle("C · 降级处理（未注册路径）")
        scenarioRow(R.id.scenario_s04, "S04 未注册路径 → onLost 降级", "路径 /not/exist（刻意不注册）· 期望：不崩溃，下方状态提示未找到并触发 onLost") {
            open(RouterContract.PATH_UNREGISTERED)
        }

        sectionTitle("D · 配置与可观测性")
        infoLine(
            "（说明）S05 isDebug 日志开关：由测试注入配置驱动（MainRouterTest#testConfigDebugMode），无需点击；" +
                "日志见 adb logcat -s TRouter。",
        )

        sectionTitle("E · 拦截器（V2.0）")
        infoLine(
            "S08/S09 为行为开关：点击行切换开/关，当前状态显示于底部状态栏；" +
                "再点 S01 观察 /second 导航差异（拦截链日志见 adb logcat -s TRouter）。",
        )
        scenarioRow(
            R.id.scenario_s08,
            "S08 门禁拦截器（Block）",
            "开启后导航 ${RouterContract.PATH_SECOND} 被拦截（Blocked、不打开）；关闭则放行",
        ) {
            DemoInterceptors.gate.enabled = !DemoInterceptors.gate.enabled
            val state = if (DemoInterceptors.gate.enabled) "已开启（将拦截 ${RouterContract.PATH_SECOND}）" else "已关闭（放行）"
            statusText.text = "门禁拦截器：$state"
            Toast.makeText(this, "门禁拦截器：$state", Toast.LENGTH_SHORT).show()
        }
        scenarioRow(
            R.id.scenario_s09,
            "S09 Mock 拦截器（Redirect）",
            "开启后 ${RouterContract.PATH_SECOND} 重定向到 Mock 页 ${RouterContract.PATH_MOCK_SECOND}；关闭恢复真实页",
        ) {
            DemoInterceptors.mock.enabled = !DemoInterceptors.mock.enabled
            val state = if (DemoInterceptors.mock.enabled) "已开启（${RouterContract.PATH_SECOND} → ${RouterContract.PATH_MOCK_SECOND}）" else "已关闭（恢复真实页）"
            statusText.text = "Mock 拦截器：$state"
            Toast.makeText(this, "Mock 拦截器：$state", Toast.LENGTH_SHORT).show()
        }

        sectionTitle("F · 跨进程导航（V4.0）")
        infoLine(
            "S11 把导航请求经 AIDL 发给 :remote 进程，由远端 TRouter 打开第二进程页面；" +
                "结果异步回传（见底部状态栏与 logcat [remote][send/recv]）。",
        )
        scenarioRow(
            R.id.scenario_s11,
            "S11 跨进程导航（Activity）",
            "路径 ${RouterContract.PATH_REMOTE_SECOND} · @CrossProcess · 期望：remote 进程页面打开并回传 Success",
        ) {
            openRemote(RouterContract.PATH_REMOTE_SECOND)
        }

        sectionTitle("G · 动态路由与图谱（V5.0）")
        infoLine(
            "S13 行切换注册/注销 /dynamic-demo（目标页未标 @Route）；注册后可点 S14 导航。" +
                "图谱摘要见下方（节点=目标类、边=已注册路由，动态与静态同图）。",
        )
        scenarioRow(
            R.id.scenario_s13,
            "S13 动态路由 · 注册/注销（切换）",
            "注册 ${RouterContract.PATH_DYNAMIC_DEMO}（group=dynamic）后 S14 可导航；再点本行注销 → 恢复 NotFound",
        ) {
            toggleDynamic()
        }
        scenarioRow(
            R.id.scenario_s14,
            "S14 导航到动态路由",
            "需先经 S13 注册：${RouterContract.PATH_DYNAMIC_DEMO} · 期望：打开 DynamicDemo 页（Success）",
        ) {
            open(RouterContract.PATH_DYNAMIC_DEMO)
        }

        sectionTitle("H · 参数透传（单进程 Activity/Fragment · 跨进程）")
        infoLine("以下行导航时携带 bundle（msg/count）；目标页会展示收到的参数，跨进程经 AIDL Bundle 送达 :remote。")
        scenarioRow(
            R.id.scenario_s19,
            "S19 单进程参数 → Second（Activity）",
            "${RouterContract.PATH_SECOND} + bundle(msg,count) · 期望：Second 页展示「参数透传 ✓」",
        ) {
            open(RouterContract.PATH_SECOND, demoParamsBundle())
        }
        scenarioRow(
            R.id.scenario_s20,
            "S20 单进程参数 → Fragment",
            "${RouterContract.PATH_FRAGMENT_DEMO} + bundle(msg,count) · 期望：Fragment 页展示收到的参数",
        ) {
            open(RouterContract.PATH_FRAGMENT_DEMO, demoParamsBundle())
        }
        scenarioRow(
            R.id.scenario_s21,
            "S21 跨进程参数 → :remote 页",
            "${RouterContract.PATH_REMOTE_SECOND} + bundle(msg,count) · 期望：第二进程页展示参数（经 AIDL）",
        ) {
            openRemote(RouterContract.PATH_REMOTE_SECOND, demoParamsBundle())
        }
        scenarioRow(
            R.id.scenario_s22,
            "S22 导航结果回传（G3）",
            "navigateForResult(${RouterContract.PATH_RESULT_DEMO}) · 期望：返回后状态栏显示结果页回传的数据",
        ) {
            openForResult(RouterContract.PATH_RESULT_DEMO)
        }

        sectionTitle("I · 异步拦截器（批次 B · S23–S25）")
        infoLine("开启 S23 后，/second 的链里会有一个异步拦截器：同步 navigate 会被**明确拒绝**（S01 变 Blocked），S24 用 navigateAsync 正常放行。")
        scenarioRow(
            R.id.scenario_s23,
            "S23 异步拦截器开关（延时 300ms 放行 /second）",
            "开启后 S01 同步导航 → Blocked（reason 提示改用 navigateAsync）；S24 仍可正常打开",
        ) {
            toggleAsyncInterceptor()
        }
        scenarioRow(
            R.id.scenario_s24,
            "S24 navigateAsync 导航（异步链正常放行）",
            "navigateAsync(${RouterContract.PATH_SECOND}) · 期望：状态栏 Success，回调在主线程且只回调一次",
        ) {
            openAsync(RouterContract.PATH_SECOND)
        }
        scenarioRow(
            R.id.scenario_s25,
            "S25 异步拦截器超时收口",
            "把异步耗时拉到 3000ms（> 配置超时 1500ms）· 期望：Blocked，reason 含「异步拦截器超时」，页面不打开",
        ) {
            openAsyncTimeout()
        }

        sectionTitle("J · 多进程（批次 C · 第三个真实进程 :remote2）")
        infoLine("host / :remote / :remote2 三个进程各有独立 TRouter 路由表与端点表；target 参数指定目标进程（null=默认 :remote）。")
        scenarioRow(
            R.id.scenario_s26,
            "S26 跨进程导航 → 第三个进程（:remote2）",
            "navigateRemote(${RouterContract.PATH_REMOTE_THIRD}, target=\"${TRouterDemoApp.REMOTE_TARGET_SECOND}\") · 期望：页面上进程名=:remote2 且 pid 与 host/:remote 都不同",
        ) {
            openRemote(RouterContract.PATH_REMOTE_THIRD, demoParamsBundle(), TRouterDemoApp.REMOTE_TARGET_SECOND)
        }
        scenarioRow(
            R.id.scenario_s27,
            "S27 端点按进程隔离（同时调 :remote2 与默认 :remote）",
            "demoClock2 只在 :remote2 注册：target=remote2 → 正常返回；默认 target → 返回未注册（证明端点表按进程独立）",
        ) {
            callSecondProcessEndpoint()
        }

        scenarioRow(
            R.id.scenario_s28,
            "S28 POJO 跨进程（KSP 生成编解码 · 免手写 Parcelable）",
            "DemoReport（含 List/枚举/可空嵌套 POJO）→ Bundle → AIDL 送到 :remote2 解包回显 · 期望：字段逐一一致",
        ) {
            sendPojoToThirdProcess()
        }

        sectionTitle("路由表快照（只读 · TRouter.registeredRoutes）")
        infoLine("（行尾 ↦ 目标类所在模块：host=:app / feature-demo / feature-about —— V3.0 多模块聚合）")
        val routes = TRouter.registeredRoutes()
        if (routes.isEmpty()) {
            infoLine("（未注册任何路由——请检查 TRouter.init/install 是否执行）", Ui.COLOR_ERROR_RED)
        } else {
            routes.forEach { meta ->
                infoLine(
                    "${meta.path} · group=${meta.group} · ${meta.kind} · " +
                        "${meta.targetClassName.substringAfterLast('.')} ↦ ${moduleTagOf(meta.targetClassName)}",
                )
            }
        }

        graphText = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.COLOR_TEXT_GRAY)
            setPadding(0, 8, 0, 4)
        }
        root.addView(graphText, contentWidth)
        refreshGraph()

        statusText = TextView(this).apply {
            id = R.id.status_text
            text = "就绪"
            textSize = 15f
            setPadding(0, 24 * d, 0, 0)
        }
        root.addView(statusText, contentWidth)

        // 内容超出一屏时可滚动；ScrollView 做系统栏避让（小屏/横屏均可用）
        val scrollView = ScrollView(this).apply {
            id = R.id.main_scroll
            addView(root)
        }
        Ui.applyEdgeInsets(scrollView, this, extraTopDp = 16, extraBottomDp = 8)
        setContentView(scrollView)
    }

    /** S19–S21 参数透传：一组确定性参数 + 保留键覆盖探针（RouteLaunch.EXTRA_PATH 冒名"成功"即失败）。 */
    private fun demoParamsBundle(): Bundle = Bundle().apply {
        putString(DemoParams.KEY_MSG, "来自主页的参数字符串")
        putInt(DemoParams.KEY_COUNT, 42)
        putString(RouteLaunch.EXTRA_PATH, "HACKED-BUNDLE-OVERRIDE") // 探针：路由元数据不得被用户参数覆盖
    }

    /** S22（G3）：navigateForResult 发起，结果在 onActivityResult 接收。 */
    private fun openForResult(path: String) {
        when (val r = TRouter.navigateForResult(path, REQUEST_RESULT_DEMO)) {
            is TRouterResult.Success -> {
                statusText.text = "已发起(等待返回):${r.meta.path}"
            }
            is TRouterResult.Blocked -> {
                statusText.text = "发起失败:${r.reason}"
                Toast.makeText(this, r.reason, Toast.LENGTH_LONG).show()
            }
            TRouterResult.NotInitialized -> statusText.text = "TRouter 未初始化"
            is TRouterResult.NotFound -> statusText.text = "未找到:${r.path}"
        }
    }

    /** S23（批次 B）：切换异步拦截器开关——开启后同步导航会被明确拒绝，这是**设计如此**。 */
    private fun toggleAsyncInterceptor() {
        val a = DemoInterceptors.async
        a.enabled = !a.enabled
        if (a.enabled) a.delayMs = 300L
        statusText.text = if (a.enabled) {
            "异步拦截器已开启（延时 ${a.delayMs}ms 放行 ${RouterContract.PATH_SECOND}）：同步 navigate 将被 Blocked"
        } else {
            "异步拦截器已关闭（链回到纯同步）"
        }
        Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
    }

    /** S24（批次 B）：navigateAsync —— 异步链正常放行，回调在主线程且只回调一次。 */
    private fun openAsync(path: String) {
        statusText.text = "navigateAsync 已发起: $path"
        TRouter.navigateAsync(path) { r ->
            val mainThread = Looper.myLooper() === Looper.getMainLooper()
            statusText.text = when (r) {
                is TRouterResult.Success -> "navigateAsync ✓ ${r.meta.path}（回调线程=主线程:$mainThread）"
                is TRouterResult.Blocked -> "navigateAsync 被拒绝: ${r.reason}"
                is TRouterResult.NotFound -> "navigateAsync 未找到: ${r.path}"
                TRouterResult.NotInitialized -> "TRouter 未初始化"
            }
            Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
        }
    }

    /** S25（批次 B）：把异步耗时拉到超过配置超时，验证超时收口 + 迟到放行被忽略。 */
    private fun openAsyncTimeout() {
        val a = DemoInterceptors.async
        a.enabled = true
        a.delayMs = 3000L
        statusText.text = "navigateAsync（异步耗时 ${a.delayMs}ms > 超时 1500ms）已发起…"
        TRouter.navigateAsync(RouterContract.PATH_SECOND) { r ->
            a.delayMs = 300L
            statusText.text = when (r) {
                is TRouterResult.Blocked -> "超时收口 ✓ ${r.reason}（此后迟到的放行被忽略）"
                is TRouterResult.Success -> "意外成功（应超时）: ${r.meta.path}"
                is TRouterResult.NotFound -> "未找到: ${r.path}"
                TRouterResult.NotInitialized -> "TRouter 未初始化"
            }
            Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
        }
    }

    /** S27（批次 C）：同一个端点名在两个进程的可见性不同——证明端点表按进程独立。 */
    private fun callSecondProcessEndpoint() {
        val args = Bundle().apply { putString("q", "from-host") }
        statusText.text = "正在调用端点 demoClock2（先 :remote2，再默认 :remote）…"
        TRouter.callRemoteService("demoClock2", args, TRouterDemoApp.REMOTE_TARGET_SECOND) { fromSecond ->
            val secondText = if (TRouter.isRemoteEndpointError(fromSecond)) "异常:$fromSecond" else fromSecond
            TRouter.callRemoteService("demoClock2", args) { fromDefault ->
                val defaultText = truncateForStatus(fromDefault)
                statusText.text = "S27 结果 · :remote2 → $secondText ｜ 默认(:remote) → $defaultText"
                Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** S28（批次 C）：POJO 本地往返 + 跨进程往返（业务类不实现 Parcelable，编解码全部由 KSP 生成）。 */
    private fun sendPojoToThirdProcess() {
        val report = DemoReport(
            id = "R-2026",
            count = 42,
            ok = true,
            tags = listOf("alpha", "beta"),
            inner = DemoInner("内层对象", DemoLevel.HIGH),
        )
        val bundle = Bundle().apply { TRouterPojo_DemoReport.pack(this, report) }
        val localRoundTrip = TRouterPojo_DemoReport.unpack(bundle) == report
        statusText.text = "S28 本地往返相等=$localRoundTrip，正在跨进程发送…"

        TRouter.callRemoteService("pojoEcho", bundle, TRouterDemoApp.REMOTE_TARGET_SECOND) { reply ->
            statusText.text = "S28 本地往返相等=$localRoundTrip ｜ 跨进程 $reply"
            Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun truncateForStatus(text: String): String =
        if (text.length <= 60) text else text.take(60) + "…"

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_RESULT_DEMO && resultCode == Activity.RESULT_OK) {
            val text = data?.getStringExtra(ResultDemoKeys.EXTRA_RESULT_TEXT) ?: "(无数据)"
            statusText.text = "收到返回结果: $text"
            Toast.makeText(this, "收到返回结果: $text", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val REQUEST_RESULT_DEMO = 1001
    }

    /** S13（V5.0）：切换注册/注销动态路由（目标页未标 @Route），并刷新图谱摘要。 */
    private fun toggleDynamic() {
        val path = RouterContract.PATH_DYNAMIC_DEMO
        val registered = TRouter.registeredRoutes().any { it.path == path }
        val ok = if (registered) {
            TRouter.unregisterRoute(path)
        } else {
            TRouter.registerRoute(
                RouteMeta(
                    path = path,
                    group = RouterContract.GROUP_DYNAMIC,
                    targetClassName = DynamicDemoActivity::class.java.name,
                    kind = RouteTargetKind.ACTIVITY,
                ),
            )
        }
        statusText.text = if (ok) {
            if (registered) "已注销动态路由:$path（恢复 NotFound）" else "已注册动态路由:$path（group=dynamic）"
        } else {
            "操作失败:$path（注册冲突或未初始化）"
        }
        Toast.makeText(this, statusText.text, Toast.LENGTH_SHORT).show()
        refreshGraph()
    }

    /** S14/图谱摘要（V5.0）：由 routeGraph() 实时汇总（动态与静态同图）。 */
    private fun refreshGraph() {
        if (!::graphText.isInitialized) return
        val g = TRouter.routeGraph()
        graphText.text = "图谱摘要：节点 ${g.nodes.size}（目标类）· 边 ${g.edges.size}（已注册路由）· 静态+动态同图"
    }

    /** S11/S21：经跨进程通道导航（V4.0，可携带 bundle 参数）——先即时反馈"请求中"，结果异步回调再回显。 */
    private fun openRemote(path: String, bundle: Bundle? = null, target: String? = null) {
        // 立即反馈：bind/远端执行是异步的，先让用户看到"已在处理"
        val targetLabel = target ?: ":remote"
        statusText.text = "跨进程请求中…（$targetLabel）"
        Toast.makeText(this, "正在向 $targetLabel 进程发起导航：$path", Toast.LENGTH_SHORT).show()
        TRouter.navigateRemote(path, bundle, target) { result ->
            runOnUiThread {
                when (result) {
                    is TRouterResult.Success -> {
                        statusText.text = "✓ 已打开(remote):${result.meta.path}"
                        Toast.makeText(this, "✓ remote 进程已打开:${result.meta.path}", Toast.LENGTH_LONG).show()
                    }
                    is TRouterResult.Blocked -> {
                        statusText.text = "已拦截(remote):${result.path}（${result.reason}）"
                        Toast.makeText(this, "已拦截(remote):${result.path}\n${result.reason}", Toast.LENGTH_LONG).show()
                    }
                    is TRouterResult.NotFound -> {
                        statusText.text = "未找到(remote):${result.path}"
                        Toast.makeText(this, "未找到(remote):${result.path}", Toast.LENGTH_SHORT).show()
                    }
                    TRouterResult.NotInitialized -> statusText.text = "TRouter 未初始化"
                }
            }
        }
    }

    /** 统一经 TRouter 导航（可携带 bundle 参数）；结果 Toast + 状态栏回显。 */
    private fun open(path: String, bundle: Bundle? = null) {
        when (val result = TRouter.navigate(path, bundle)) {
            is TRouterResult.Success -> {
                statusText.text = "已打开:${result.meta.path}"
                Toast.makeText(this, "✓ 已打开:${result.meta.path}（${result.meta.kind}）", Toast.LENGTH_SHORT).show()
            }
            is TRouterResult.NotFound -> {
                statusText.text = "未找到路由:${result.path}"
                Toast.makeText(this, "未找到路由:${result.path}", Toast.LENGTH_SHORT).show()
            }
            is TRouterResult.Blocked -> {
                statusText.text = "已拦截:${result.path}（${result.reason}）"
                Toast.makeText(this, "已拦截:${result.path}\n${result.reason}", Toast.LENGTH_LONG).show()
            }
            TRouterResult.NotInitialized -> statusText.text = "TRouter 未初始化"
        }
    }
}
