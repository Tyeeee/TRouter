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
import com.demo.trouter.backtest.BacktestContract
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
 * TRouter 最早的基础版本 测试台 · 场景索引（主页，@Route /main）。
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

        /** 多模块版本 多模块可见化：按目标类包名推导来源模块（纯 UI 展示，不改 core 数据模型）。 */
        fun moduleTagOf(className: String): String = when {
            className.contains(".feature.demo.") -> "feature-demo"
            className.contains(".feature.about.") -> "feature-about"
            else -> "host"
        }

        root.addView(TextView(this).apply {
            text = "TRouter 演示 App（每一行点进去都能看到效果）"
            textSize = 22f
        })
        infoLine(
            "下面每一行是一个可点的例子，点进去就能看到效果；" +
                "想看某个功能怎么用、会看到什么，看行里的说明即可。返回用页面里的「返回」按钮或系统返回键。",
        )

        sectionTitle("A · 最基本的跳转：打开一个页面（S01–S04）")
        scenarioRow(R.id.scenario_s01, "S01 打开一个新页面", "路径 /second · default · 期望：Second 页展示，navigate 返回 Success") {
            open(RouterContract.PATH_SECOND)
        }
        scenarioRow(R.id.scenario_s02, "S02 打开一个「页面片段」（Fragment）", "路径 /fragment-demo · default · 期望：Fragment 演示页经 core 容器展示，Success(kind=FRAGMENT)") {
            open(RouterContract.PATH_FRAGMENT_DEMO)
        }
        infoLine("（说明）本页自己也是一个已注册的页面（路径 /main），所以别人也能用路由打开它——无需点击。")

        sectionTitle("B · 跨模块跳转：打开另一个模块的页面（S03）")
        scenarioRow(R.id.scenario_s03, "S03 打开另一个模块里的页面", "路径 /about · group=secondary · 期望：页面展示，日志出现 group=secondary 加载起止") {
            open(RouterContract.PATH_ABOUT)
        }

        sectionTitle("C · 页面不存在时会怎样（S04）")
        scenarioRow(R.id.scenario_s04, "S04 打开一个不存在的页面（看兜底提示）", "路径 /not/exist（刻意不注册）· 期望：不崩溃，下方状态提示未找到并触发 onLost") {
            open(RouterContract.PATH_UNREGISTERED)
        }

        sectionTitle("D · 日志与「这页是不是路由打开的」")
        infoLine(
            "（说明）日志开关由配置里的 isDebug 控制：true 时打印全过程，false 时安静。" +
                "想看日志：adb logcat -s TRouter。",
        )

        sectionTitle("E · 跳转前先做检查：拦截（S08–S10）")
        infoLine(
            "S08/S09 是开关：点一下打开、再点一下关闭，当前状态会显示在页面底部；" +
                "开关打开后，去点 S01 就能看到差别。",
        )
        scenarioRow(
            R.id.scenario_s08,
            "S08 开关：把某个页面临时拦住（像登录校验）",
            "开启后导航 ${RouterContract.PATH_SECOND} 被拦截（Blocked、不打开）；关闭则放行",
        ) {
            DemoInterceptors.gate.enabled = !DemoInterceptors.gate.enabled
            val state = if (DemoInterceptors.gate.enabled) "已开启（将拦截 ${RouterContract.PATH_SECOND}）" else "已关闭（放行）"
            statusText.text = "门禁拦截器：$state"
            Toast.makeText(this, "门禁拦截器：$state", Toast.LENGTH_SHORT).show()
        }
        scenarioRow(
            R.id.scenario_s09,
            "S09 开关：把一个页面换成另一个页面（灰度/Mock）",
            "开启后 ${RouterContract.PATH_SECOND} 重定向到 Mock 页 ${RouterContract.PATH_MOCK_SECOND}；关闭恢复真实页",
        ) {
            DemoInterceptors.mock.enabled = !DemoInterceptors.mock.enabled
            val state = if (DemoInterceptors.mock.enabled) "已开启（${RouterContract.PATH_SECOND} → ${RouterContract.PATH_MOCK_SECOND}）" else "已关闭（恢复真实页）"
            statusText.text = "Mock 拦截器：$state"
            Toast.makeText(this, "Mock 拦截器：$state", Toast.LENGTH_SHORT).show()
        }

        sectionTitle("F · 把页面开在另一个进程里（S11–S12）")
        infoLine(
            "点 S11 后，本进程会把「要打开哪个页面」这件事发给第二个进程，由那边的 TRouter 打开页面，" +
                "再把结果回传到这里（底部状态栏会显示结果）。",
        )
        scenarioRow(
            R.id.scenario_s11,
            "S11 把页面开在第二个进程里",
            "路径 ${RouterContract.PATH_REMOTE_SECOND} · @CrossProcess · 期望：remote 进程页面打开并回传 Success",
        ) {
            openRemote(RouterContract.PATH_REMOTE_SECOND)
        }

        sectionTitle("G · 运行时才注册的页面（S13–S14）")
        infoLine(
            "点 S13 会在「运行时」注册一条路径（对应页面没有加注解），再点一下注销；注册后点 S14 就能打开它。" +
                "下方还列出了当前所有路径。",
        )
        scenarioRow(
            R.id.scenario_s13,
            "S13 开关：运行时注册 / 注销一条路径",
            "注册 ${RouterContract.PATH_DYNAMIC_DEMO}（group=dynamic）后 S14 可导航；再点本行注销 → 恢复 NotFound",
        ) {
            toggleDynamic()
        }
        scenarioRow(
            R.id.scenario_s14,
            "S14 打开刚注册的那条路径",
            "需先经 S13 注册：${RouterContract.PATH_DYNAMIC_DEMO} · 期望：打开 DynamicDemo 页（Success）",
        ) {
            open(RouterContract.PATH_DYNAMIC_DEMO)
        }

        sectionTitle("H · 跳转时怎么带参数（S19–S21）")
        infoLine("下面三行跳转时都会带上两个参数（一段文字 + 一个数字），目标页会把收到的参数显示出来。")
        scenarioRow(
            R.id.scenario_s19,
            "S19 带参数跳转 → Activity 页",
            "${RouterContract.PATH_SECOND} + bundle(msg,count) · 期望：Second 页展示「参数透传 ✓」",
        ) {
            open(RouterContract.PATH_SECOND, demoParamsBundle())
        }
        scenarioRow(
            R.id.scenario_s20,
            "S20 带参数跳转 → Fragment 页",
            "${RouterContract.PATH_FRAGMENT_DEMO} + bundle(msg,count) · 期望：Fragment 页展示收到的参数",
        ) {
            open(RouterContract.PATH_FRAGMENT_DEMO, demoParamsBundle())
        }
        scenarioRow(
            R.id.scenario_s21,
            "S21 带参数跳到第二个进程的页面",
            "${RouterContract.PATH_REMOTE_SECOND} + bundle(msg,count) · 期望：第二进程页展示参数（经 AIDL）",
        ) {
            openRemote(RouterContract.PATH_REMOTE_SECOND, demoParamsBundle())
        }
        scenarioRow(
            R.id.scenario_s22,
            "S22 打开页面，并在它返回时拿到数据",
            "navigateForResult(${RouterContract.PATH_RESULT_DEMO}) · 期望：返回后状态栏显示结果页回传的数据",
        ) {
            openForResult(RouterContract.PATH_RESULT_DEMO)
        }

        sectionTitle("I · 跳转前需要联网/等待怎么办（S23–S25）")
        infoLine("点 S23 打开开关后，「打开页面之前要先等一下」这件事就生效了：这时用普通方式跳转会被拒绝（并告诉你原因），请改用 S24 的异步方式。")
        scenarioRow(
            R.id.scenario_s23,
            "S23 开关：跳转前先「等一会儿」（模拟联网检查）",
            "开启后 S01 同步导航 → Blocked（reason 提示改用 navigateAsync）；S24 仍可正常打开",
        ) {
            toggleAsyncInterceptor()
        }
        scenarioRow(
            R.id.scenario_s24,
            "S24 等待检查完成后再打开页面",
            "navigateAsync(${RouterContract.PATH_SECOND}) · 期望：状态栏 Success，回调在主线程且只回调一次",
        ) {
            openAsync(RouterContract.PATH_SECOND)
        }
        scenarioRow(
            R.id.scenario_s25,
            "S25 检查一直没结果会怎样（超时保护）",
            "把异步耗时拉到 3000ms（> 配置超时 1500ms）· 期望：Blocked，reason 含「异步拦截器超时」，页面不打开",
        ) {
            openAsyncTimeout()
        }

        sectionTitle("J · 多个进程各自独立（S26–S29）")
        infoLine("本 App 一共有三个进程：主进程、第二个进程、第三个进程。每个进程都有自己的路径表和接口表，互不影响；下面是分别在第三个进程里打开页面、调用接口。")
        scenarioRow(
            R.id.scenario_s26,
            "S26 把页面开在第三个进程里",
            "navigateRemote(${RouterContract.PATH_REMOTE_THIRD}, target=\"${TRouterDemoApp.REMOTE_TARGET_SECOND}\") · 期望：页面上进程名=:remote2 且 pid 与 host/:remote 都不同",
        ) {
            openRemote(RouterContract.PATH_REMOTE_THIRD, demoParamsBundle(), TRouterDemoApp.REMOTE_TARGET_SECOND)
        }
        scenarioRow(
            R.id.scenario_s27,
            "S27 两个进程各有自己的接口，互不串台",
            "demoClock2 只在 :remote2 注册：target=remote2 → 正常返回；默认 target → 返回未注册（证明端点表按进程独立）",
        ) {
            callSecondProcessEndpoint()
        }

        scenarioRow(
            R.id.scenario_s28,
            "S28 跨进程传一个业务对象（不用手写序列化）",
            "DemoReport（含 List/枚举/可空嵌套 POJO）→ Bundle → AIDL 送到 :remote2 解包回显 · 期望：字段逐一一致",
        ) {
            sendPojoToThirdProcess()
        }

        scenarioRow(
            R.id.scenario_s29,
            "S29 像调本地接口一样，调另一个进程里的接口",
            "连续三次类型化调用 count / summarize / report（含枚举、List、POJO 结果）· 期望：结果逐项正确且带远端 pid",
        ) {
            callTypedRemoteApi()
        }

        sectionTitle("K · 回测台：把每个功能点都真的跑一遍（S30）")
        infoLine(
            "回测台是一组「测试节点」：每个节点对应一个功能点，点一下就真的发起跳转、真的打开页面、真的跨进程调用，" +
                "再对照屏幕上究竟发生了什么给结论（通过/失败 + 证据行）。可以一键全跑，也可以只跑某一条。",
        )
        scenarioRow(
            R.id.scenario_s30,
            "S30 打开回测台（全部功能点逐条实测）",
            "路径 ${BacktestContract.PATH_CONSOLE} · 期望：进入回测台，看到节点清单与「开始回测」按钮",
        ) {
            open(BacktestContract.PATH_CONSOLE)
        }

        sectionTitle("当前已注册的全部路径（只读）")
        infoLine("（每行末尾的 ↦ 表示这个页面来自哪个模块：:app 主模块 / feature-demo / feature-about）")
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

    /** S22（拿页面返回值）：navigateForResult 发起，结果在 onActivityResult 接收。 */
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

    /** S23（异步拦截器改造）：切换异步拦截器开关——开启后同步导航会被明确拒绝，这是**设计如此**。 */
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

    /** S24（异步拦截器改造）：navigateAsync —— 异步链正常放行，回调在主线程且只回调一次。 */
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

    /** S25（异步拦截器改造）：把异步耗时拉到超过配置超时，验证超时收口 + 迟到放行被忽略。 */
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

    /** S27（多进程与跨进程增强）：同一个端点名在两个进程的可见性不同——证明端点表按进程独立。 */
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

    /** S28（多进程与跨进程增强）：POJO 本地往返 + 跨进程往返（业务类不实现 Parcelable，编解码全部由 KSP 生成）。 */
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

    /** S29（多进程与跨进程增强）：类型化远程接口——调用点看不到 Bundle/字符串协议，只有接口方法。 */
    private fun callTypedRemoteApi() {
        val api = TRouter.remoteApi(
            DemoStatsApi::class.java,
            TRouterDemoApp.REMOTE_TARGET_SECOND,
        ) { reason ->
            statusText.text = "S29 类型化调用失败: $reason"
            Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
        }
        statusText.text = "S29 类型化调用中（count → summarize → report）…"

        api.count("abcd") { n ->
            api.summarize("s29", DemoLevel.HIGH, listOf(1, 2, 3)) { summary ->
                api.report("X") { report ->
                    statusText.text = "S29 ✓ count=$n ｜ $summary ｜ report=${report.id}/count=${report.count}/" +
                        "tags=${report.tags.joinToString("|")}/inner=${report.inner?.name}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
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

    /** S13（运行时注册路径版本）：切换注册/注销动态路由（目标页未标 @Route），并刷新图谱摘要。 */
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

    /** S14/图谱摘要（运行时注册路径版本）：由 routeGraph() 实时汇总（动态与静态同图）。 */
    private fun refreshGraph() {
        if (!::graphText.isInitialized) return
        val g = TRouter.routeGraph()
        graphText.text = "图谱摘要：节点 ${g.nodes.size}（目标类）· 边 ${g.edges.size}（已注册路由）· 静态+动态同图"
    }

    /** S11/S21：经跨进程通道导航（跨进程版本，可携带 bundle 参数）——先即时反馈"请求中"，结果异步回调再回显。 */
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
