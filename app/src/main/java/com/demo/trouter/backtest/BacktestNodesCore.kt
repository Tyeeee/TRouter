package com.demo.trouter.backtest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.demo.trouter.DemoInterceptors
import com.demo.trouter.ResultDemoKeys
import com.demo.trouter.ResultEchoActivity
import com.demo.trouter.feature.about.AboutActivity
import com.demo.trouter.feature.demo.DemoFragment
import com.demo.trouter.feature.demo.SecondActivity
import com.trouter.core.api.DemoParams
import com.trouter.core.api.RouteLaunch
import com.trouter.core.api.RouteTargetKind
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterIntent
import com.trouter.core.api.TRouterResult
import com.trouter.core.internal.FragmentContainerActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 回测节点 · 第一批：主流程。
 *
 * 分组：
 * - A 基础跳转与页面落地：验"页面真的出现了，参数真的到了"
 * - B 参数透传与保留键：验"用户参数不会冒充路由元数据"
 * - C 页面返回值：验"页面里的返回数据真的回到了发起方"
 *
 * 原则：断言尽量落在**可见效果**上（哪个页面被切到前台、它拿到的 intent 里有什么、
 * 页面上的文字是什么），navigate() 的返回值只作为旁证记录进证据。
 */
internal object BacktestNodesCore {

    const val F_A = "A · 基础跳转与页面落地"
    const val F_B = "B · 参数透传与保留键"
    const val F_C = "C · 页面返回值（navigateForResult）"

    val nodes: List<BacktestNode> = listOf(
        a01(), a02(), a03(), a04(), a05(), a06(), a07(),
        b01(), b02(), b03(), b04(),
        c01(), c02(), c03(), c04(), c05(), c06(), c07(),
    )

    // ------------------------------------------------------------------ A

    private fun a01() = BacktestNode(
        id = "A01",
        feature = F_A,
        title = "打开 /second：页面真的出现，并带着路由元数据",
        expected = "屏幕出现 SecondActivity；它真实收到的 path=/second、kind=ACTIVITY、group=default、traceId 非空；按返回回到回测台",
    ) { ctx ->
        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
        ctx.require(result is TRouterResult.Success, "navigate 应返回 Success，实际=$result")
        ctx.note("navigate 返回=${result.javaClass.simpleName}")

        val page = ctx.awaitPage(SecondActivity::class.java, mark)
        ctx.expect(page.intent.getStringExtra(RouteLaunch.EXTRA_PATH), RouterContract.PATH_SECOND, "页面真实收到的 path")
        ctx.expect(page.intent.getStringExtra(RouteLaunch.EXTRA_KIND), RouteTargetKind.ACTIVITY.name, "页面真实收到的 kind")
        ctx.expect(page.intent.getStringExtra(RouteLaunch.EXTRA_GROUP), RouterContract.GROUP_DEFAULT, "页面真实收到的 group")
        ctx.require(
            !page.intent.getStringExtra(RouteLaunch.EXTRA_TRACE_ID).isNullOrBlank(),
            "页面应拿到 traceId（可观测性契约），实际=${page.intent.getStringExtra(RouteLaunch.EXTRA_TRACE_ID)}",
        )
        ctx.note("traceId=${page.intent.getStringExtra(RouteLaunch.EXTRA_TRACE_ID)}")

        ctx.finishPage(page)
        ctx.awaitHostBack()
        ctx.note("按返回后回到回测台：${ctx.hostActivity().javaClass.simpleName}")
    }

    private fun a02() = BacktestNode(
        id = "A02",
        feature = F_A,
        title = "打开 /fragment-demo：容器页出现，片段真的被装进去且拿到参数",
        expected = "屏幕出现 FragmentContainerActivity，里面真的装着 DemoFragment；片段 arguments 里的 path/kind 正确，且容器内部键没有泄漏成业务参数",
    ) { ctx ->
        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_FRAGMENT_DEMO) }
        ctx.require(result is TRouterResult.Success, "navigate 应返回 Success，实际=$result")
        ctx.expect((result as TRouterResult.Success).meta.kind, RouteTargetKind.FRAGMENT, "navigate 返回的 kind")

        val container = ctx.awaitPage(FragmentContainerActivity::class.java, mark) as FragmentActivity
        val fragment = ctx.awaitFragment(container)
        ctx.require(fragment is DemoFragment, "容器里装的应是 DemoFragment，实际=${fragment?.javaClass?.name}")
        val args = fragment!!.arguments
        ctx.expect(args?.getString(RouteLaunch.EXTRA_PATH), RouterContract.PATH_FRAGMENT_DEMO, "片段真实收到的 path")
        ctx.expect(args?.getString(RouteLaunch.EXTRA_KIND), RouteTargetKind.FRAGMENT.name, "片段真实收到的 kind")
        ctx.require(
            args?.containsKey(FragmentContainerActivity.EXTRA_FRAGMENT_CLASS) != true,
            "容器内部键不应泄漏进业务参数，实际 keys=${args?.keySet()}",
        )
        ctx.note("片段参数 keys=${args?.keySet()?.toList()}")

        ctx.finishPage(container)
        ctx.awaitHostBack()
    }

    private fun a03() = BacktestNode(
        id = "A03",
        feature = F_A,
        title = "跨模块打开 /about：另一个模块的页面被打开",
        expected = "屏幕出现 feature-about 模块里的 AboutActivity（调用方完全没有 import 它）",
    ) { ctx ->
        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_ABOUT) }
        ctx.require(result is TRouterResult.Success, "navigate 应返回 Success，实际=$result")

        val page = ctx.awaitPage(AboutActivity::class.java, mark)
        ctx.expectContains(page.javaClass.name, "com.demo.trouter.feature.about", "目标页所在模块包名")
        ctx.expect(page.intent.getStringExtra(RouteLaunch.EXTRA_GROUP), RouterContract.GROUP_SECONDARY, "页面真实收到的 group")
        ctx.note("目标类=${page.javaClass.name}")

        ctx.finishPage(page)
        ctx.awaitHostBack()
    }

    private fun a04() = BacktestNode(
        id = "A04",
        feature = F_A,
        title = "自己也是路由页：打开 /main",
        expected = "回测台本身也是注册页面；导航 /main 后主页真的出现，返回后回到回测台",
    ) { ctx ->
        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_MAIN) }
        ctx.require(result is TRouterResult.Success, "navigate /main 应返回 Success，实际=$result")
        val page = ctx.awaitPage(com.demo.trouter.MainActivity::class.java, mark)
        ctx.note("主页实例=${page.hashCode()}，单任务模式=${page.intent.flags}")
        ctx.finishPage(page)
        ctx.awaitHostBack()
    }

    private fun a05() = BacktestNode(
        id = "A05",
        feature = F_A,
        title = "打开不存在的路径：不崩溃、不打开页面、兜底回调触发",
        expected = "navigate 返回 NotFound；没有任何页面被打开；配置的 onLost 兜底回调真的收到了这个 path；日志里留下 NotFound 出口",
    ) { ctx ->
        ctx.clearLostPaths()
        val logMark = ctx.logMark()
        val mark = ctx.mark()

        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_UNREGISTERED) }
        ctx.require(result is TRouterResult.NotFound, "应返回 NotFound，实际=$result")
        ctx.expect((result as TRouterResult.NotFound).path, RouterContract.PATH_UNREGISTERED, "NotFound 携带的 path")

        ctx.expectNoPageOpened(mark)
        ctx.require(
            ctx.lostPathsSnapshot().contains(RouterContract.PATH_UNREGISTERED),
            "onLost 应收到该 path，实际收到 ${ctx.lostPathsSnapshot()}",
        )
        ctx.note("onLost 收到=${ctx.lostPathsSnapshot()}")
        ctx.requireLog(logMark, "result=NotFound", "NotFound 出口日志")
    }

    private fun a06() = BacktestNode(
        id = "A06",
        feature = F_A,
        title = "带参数打开 /second：目标页真的收到了参数",
        expected = "目标页 intent 里 msg/count 与调用方传的一致（不是默认值）",
    ) { ctx ->
        val mark = ctx.mark()
        val bundle = Bundle().apply {
            putString(DemoParams.KEY_MSG, "A06 的参数")
            putInt(DemoParams.KEY_COUNT, 66)
        }
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND, bundle) }
        ctx.require(result is TRouterResult.Success, "navigate 应返回 Success，实际=$result")

        val page = ctx.awaitPage(SecondActivity::class.java, mark)
        val pageArgs = com.trouter.core.api.RouteArgs.of(page.intent)
        ctx.expect(pageArgs.str(DemoParams.KEY_MSG), "A06 的参数", "目标页真实收到的 msg")
        ctx.expect(pageArgs.int(DemoParams.KEY_COUNT, -1), 66, "目标页真实收到的 count")
        ctx.finishPage(page)
        ctx.awaitHostBack()
    }

    private fun a07() = BacktestNode(
        id = "A07",
        feature = F_A,
        title = "连续打开同一个页面两次：两次都真的打开，不串页",
        expected = "两个 SecondActivity 实例先后被切到前台；全部返回后回到回测台",
    ) { ctx ->
        val mark = ctx.mark()
        ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
        ctx.awaitPage(SecondActivity::class.java, mark)
        val secondMark = ctx.mark()
        ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
        ctx.awaitPage(SecondActivity::class.java, secondMark)

        val count = ctx.countPagesSince(SecondActivity::class.java, mark)
        ctx.expect(count, 2, "被打开的次数")
        ctx.closeAllPagesSince(mark)
        ctx.awaitHostBack()
    }

    // ------------------------------------------------------------------ B

    private fun b01() = BacktestNode(
        id = "B01",
        feature = F_B,
        title = "参数操作页：页面自己收到参数并显示出来",
        expected = "回测台导航到 /bt-form 并传参；表单页上真实渲染出「参数透传 ✓ msg=… count=…」",
    ) { ctx ->
        val mark = ctx.mark()
        val bundle = Bundle().apply {
            putString(DemoParams.KEY_MSG, "B01 给表单页的参数")
            putInt(DemoParams.KEY_COUNT, 11)
        }
        val result = ctx.mainValue { TRouter.navigate(BacktestContract.PATH_FORM, bundle) }
        ctx.require(result is TRouterResult.Success, "navigate 应返回 Success，实际=$result")

        val page = ctx.awaitPage(BacktestFormActivity::class.java, mark) as BacktestFormActivity
        val onScreen = page.evidenceLine()
        ctx.note("表单页屏幕文本=${onScreen.replace('\n', ' ')}")
        ctx.expectContains(onScreen, "参数透传 ✓", "表单页屏幕上的一行")
        ctx.expectContains(onScreen, "B01 给表单页的参数", "表单页屏幕上的一行")
        ctx.expectContains(onScreen, "count=11", "表单页屏幕上的一行")
    }

    private fun b02() = BacktestNode(
        id = "B02",
        feature = F_B,
        title = "由页面发起同步跳转：输入框里的参数真的带到下一页",
        expected = "在表单页填入参数后点「同步跳转」，/second 真的打开且收到的正是输入框里的值；表单页上的结果行显示成功",
    ) { ctx ->
        val formMark = ctx.mark()
        ctx.mainValue { TRouter.navigate(BacktestContract.PATH_FORM, null) }
        val form = ctx.awaitPage(BacktestFormActivity::class.java, formMark) as BacktestFormActivity

        val openMark = ctx.mark()
        val result = ctx.mainValue {
            form.setInputsForTest("B02 输入框里的值", 22)
            form.clickSync()
        }
        ctx.require(result is TRouterResult.Success, "表单页同步跳转应 Success，实际=$result")
        ctx.expectContains(form.resultLine(), "跳转成功 ✓", "表单页结果行")

        val second = ctx.awaitPage(SecondActivity::class.java, openMark)
        val args = com.trouter.core.api.RouteArgs.of(second.intent)
        ctx.expect(args.str(DemoParams.KEY_MSG), "B02 输入框里的值", "下一页真实收到的 msg")
        ctx.expect(args.int(DemoParams.KEY_COUNT, -1), 22, "下一页真实收到的 count")

        ctx.finishPage(second)
        ctx.awaitHostBackOf(form)
        ctx.finishPage(form)
        ctx.awaitHostBack()
    }

    private fun b03() = BacktestNode(
        id = "B03",
        feature = F_B,
        title = "由页面发起异步跳转：同样把参数带到下一页",
        expected = "表单页点「异步跳转」后 /second 真的打开，参数一致，异步回调在页面上显示成功",
    ) { ctx ->
        val formMark = ctx.mark()
        ctx.mainValue { TRouter.navigate(BacktestContract.PATH_FORM, null) }
        val form = ctx.awaitPage(BacktestFormActivity::class.java, formMark) as BacktestFormActivity

        val openMark = ctx.mark()
        val latch = CountDownLatch(1)
        var result: TRouterResult? = null
        ctx.main {
            form.setInputsForTest("B03 异步参数", 33)
            form.clickAsync { r -> result = r; latch.countDown() }
        }
        ctx.require(latch.await(8, TimeUnit.SECONDS), "表单页异步跳转回调超时")
        ctx.require(result is TRouterResult.Success, "异步跳转应 Success，实际=$result")

        val second = ctx.awaitPage(SecondActivity::class.java, openMark)
        val args = com.trouter.core.api.RouteArgs.of(second.intent)
        ctx.expect(args.str(DemoParams.KEY_MSG), "B03 异步参数", "下一页真实收到的 msg")

        ctx.finishPage(second)
        ctx.awaitHostBackOf(form)
        ctx.finishPage(form)
        ctx.awaitHostBack()
    }

    private fun b04() = BacktestNode(
        id = "B04",
        feature = F_B,
        title = "由页面打开不存在的路径：页面上显示未找到，且没有任何页面被打开",
        expected = "表单页结果行出现「未找到路径」；屏幕上不出现任何新页面",
    ) { ctx ->
        val formMark = ctx.mark()
        ctx.mainValue { TRouter.navigate(BacktestContract.PATH_FORM, null) }
        val form = ctx.awaitPage(BacktestFormActivity::class.java, formMark) as BacktestFormActivity

        val openMark = ctx.mark()
        val result = ctx.mainValue { form.clickNotFound() }
        ctx.require(result is TRouterResult.NotFound, "应返回 NotFound，实际=$result")
        ctx.expectContains(form.resultLine(), "未找到路径", "表单页结果行")
        ctx.expectNoPageOpened(openMark)

        ctx.finishPage(form)
        ctx.awaitHostBack()
    }

    // ------------------------------------------------------------------ C

    private fun c01() = BacktestNode(
        id = "C01",
        feature = F_C,
        title = "要返回值地打开页面：页面回传的数据真的回到发起方",
        expected = "ResultEchoActivity 被打开；它 setResult(RESULT_OK, 数据) 后，回测台的 onActivityResult 收到同一段文字",
    ) { ctx ->
        val mark = ctx.mark()
        // 页面打开后由后台线程扮演"用户点了返回并携带结果"
        ctx.background {
            val page = ctx.awaitPage(ResultEchoActivity::class.java, mark, 8_000)
            ctx.sleep(200)
            ctx.finishPageWithResult(
                page,
                Activity.RESULT_OK,
                Intent().putExtra(ResultDemoKeys.EXTRA_RESULT_TEXT, "C01 页面回传的数据"),
            )
        }
        val (code, data) = ctx.awaitCallback<Pair<Int, Intent?>>(10_000, "onActivityResult 回传") { cb ->
            val r = ctx.host.launchForResult(RouterContract.PATH_RESULT_DEMO, REQ_C01, null) { c, d -> cb(c to d) }
            ctx.require(r is TRouterResult.Success, "navigateForResult 应 Success，实际=$r")
        }
        ctx.assertBackgroundOk()
        ctx.expect(code, Activity.RESULT_OK, "回传的 resultCode")
        ctx.expect(data?.getStringExtra(ResultDemoKeys.EXTRA_RESULT_TEXT), "C01 页面回传的数据", "回传的文本")
        ctx.awaitHostBack()
    }

    private fun c02() = BacktestNode(
        id = "C02",
        feature = F_C,
        title = "页面直接返回（不带结果）：发起方只拿到取消，不会拿到假数据",
        expected = "ResultEchoActivity 被打开后直接返回，回测台收到 RESULT_CANCELED（不是 RESULT_OK）",
    ) { ctx ->
        val mark = ctx.mark()
        ctx.background {
            val page = ctx.awaitPage(ResultEchoActivity::class.java, mark, 8_000)
            ctx.sleep(200)
            ctx.finishPage(page)
        }
        val (code, data) = ctx.awaitCallback<Pair<Int, Intent?>>(10_000, "onActivityResult 回传") { cb ->
            ctx.host.launchForResult(RouterContract.PATH_RESULT_DEMO, REQ_C02, null) { c, d -> cb(c to d) }
        }
        ctx.assertBackgroundOk()
        ctx.expect(code, Activity.RESULT_CANCELED, "回传的 resultCode")
        ctx.require(
            data?.getStringExtra(ResultDemoKeys.EXTRA_RESULT_TEXT) == null,
            "直接返回不应携带结果数据，实际=${data?.extras}",
        )
        ctx.awaitHostBack()
    }

    private fun c03() = BacktestNode(
        id = "C03",
        feature = F_C,
        title = "要返回值地打开一个不存在的路径：明确报错，不打开页面",
        expected = "navigateForResult 返回 NotFound，屏幕上不出现任何新页面",
    ) { ctx ->
        val mark = ctx.mark()
        val result = ctx.mainValue {
            TRouter.navigateForResult(RouterContract.PATH_UNREGISTERED, REQ_C03, null)
        }
        ctx.require(result is TRouterResult.NotFound, "应返回 NotFound，实际=$result")
        ctx.expectNoPageOpened(mark)
    }

    private fun c04() = BacktestNode(
        id = "C04",
        feature = F_C,
        title = "中继页：页面里再打开一个页面，并把中继证据回传",
        expected = "回测台 → 中继页 → /second 三跳链路成立；/second 返回后中继页回到前台；中继页回传的证据里写明它成功打开了 /second",
    ) { ctx ->
        val mark = ctx.mark()
        val bundle = Bundle().apply {
            putString(BacktestContract.KEY_RELAY_TARGET, RouterContract.PATH_SECOND)
        }
        ctx.background {
            val second = ctx.awaitPage(SecondActivity::class.java, mark, 20_000)
            ctx.sleep(200)
            ctx.finishPage(second)
            val relay = ctx.awaitPage(BacktestRelayActivity::class.java, ctx.mark(), 20_000)
            ctx.sleep(200)
            ctx.main { (relay as BacktestRelayActivity).returnWithEvidence() }
        }
        val (code, data) = ctx.awaitCallback<Pair<Int, Intent?>>(30_000, "中继页回传证据") { cb ->
            ctx.host.launchForResult(BacktestContract.PATH_RELAY, REQ_C04, bundle) { c, d -> cb(c to d) }
        }
        ctx.assertBackgroundOk()
        ctx.expect(code, Activity.RESULT_OK, "中继页回传的 resultCode")
        val payload = data?.getStringExtra(BacktestContract.KEY_RELAY_RESULT) ?: "(没有证据)"
        ctx.note("中继证据=$payload")
        ctx.expectContains(payload, "成功 ${RouterContract.PATH_SECOND}", "中继页自己发起的第二跳结果")
        ctx.awaitHostBack()
    }

    private fun c05() = BacktestNode(
        id = "C05",
        feature = F_C,
        title = "产出 Intent 交给现代 Activity Result API（registerForActivityResult）",
        expected = "buildIntent 产出的 Intent 带着完整路由元数据；用 registerForActivityResult 注册的 launcher 启动它，" +
            "页面真的打开，且结果**只**从 launcher 回调回来（完全不碰 onActivityResult）",
    ) { ctx ->
        val logMark = ctx.logMark()
        val built = ctx.mainValue { TRouter.buildIntent(RouterContract.PATH_RESULT_DEMO) }
        ctx.noteNavigateLogs(logMark, "buildIntent")
        ctx.require(built is TRouterIntent.Ready, "应产出 Intent，实际=${describeIntent(built)}")
        val ready = built as TRouterIntent.Ready
        // 元数据必须已经写好（目标页靠它自述"我是被路由打开的"）
        ctx.expect(ready.intent.getStringExtra(RouteLaunch.EXTRA_PATH), RouterContract.PATH_RESULT_DEMO, "Intent 里的 path")
        ctx.expect(ready.intent.getStringExtra(RouteLaunch.EXTRA_KIND), RouteTargetKind.ACTIVITY.name, "Intent 里的 kind")
        ctx.expect(ready.intent.getStringExtra(RouteLaunch.EXTRA_GROUP), RouterContract.GROUP_DEFAULT, "Intent 里的 group")
        ctx.require(ready.intent.component?.className == ResultEchoActivity::class.java.name, "Intent 应指向目标页：${ready.intent.component}")

        val mark = ctx.mark()
        // 页面打开后由后台线程扮演"用户点了返回并携带结果"
        ctx.background {
            val page = ctx.awaitPage(ResultEchoActivity::class.java, mark, 20_000)
            ctx.sleep(200)
            ctx.finishPageWithResult(
                page,
                Activity.RESULT_OK,
                Intent().putExtra(ResultDemoKeys.EXTRA_RESULT_TEXT, "C05 现代 API 回传的数据"),
            )
        }
        val (code, data) = ctx.awaitCallback<Pair<Int, Intent?>>(30_000, "ActivityResultLauncher 回调") { cb ->
            ctx.host.launchWithActivityResult(ready.intent) { c, d -> cb(c to d) }
        }
        ctx.assertBackgroundOk()
        ctx.expect(code, Activity.RESULT_OK, "launcher 收到的 resultCode")
        ctx.expect(
            data?.getStringExtra(ResultDemoKeys.EXTRA_RESULT_TEXT),
            "C05 现代 API 回传的数据",
            "launcher 收到的数据",
        )
        ctx.note("结果来自 registerForActivityResult 的注册回调（没有用 onActivityResult / requestCode）")
        ctx.awaitHostBack()
    }

    private fun c06() = BacktestNode(
        id = "C06",
        feature = F_C,
        title = "buildIntent 与 navigate 同语义：未注册/被拦/别名 一致生效",
        expected = "未注册路径不产出 Intent（NotFound）；门禁拦截时不产出 Intent（Blocked）且页面不会打开；别名解析照样生效（Intent 指向真实路径）",
    ) { ctx ->
        // 1) 未注册路径
        val missing = ctx.mainValue { TRouter.buildIntent(RouterContract.PATH_UNREGISTERED) }
        ctx.require(missing is TRouterIntent.NotFound, "未注册路径应 NotFound，实际=${describeIntent(missing)}")

        // 2) 被拦截：不产出 Intent，也不许偷偷打开页面
        DemoInterceptors.gate.enabled = true
        val mark = ctx.mark()
        val blocked = ctx.mainValue { TRouter.buildIntent(RouterContract.PATH_SECOND) }
        ctx.require(blocked is TRouterIntent.Blocked, "被拦截应 Blocked，实际=${describeIntent(blocked)}")
        ctx.expectContains((blocked as TRouterIntent.Blocked).reason, "门禁", "拦截原因")
        ctx.expectNoPageOpened(mark, quietMs = 800)
        DemoInterceptors.gate.enabled = false

        // 3) 别名解析
        ctx.require(
            TRouter.registerRouteAlias(BacktestContract.ALIAS_LEGACY_SECOND, RouterContract.PATH_SECOND),
            "别名注册应成功",
        )
        ctx.onCleanup { TRouter.unregisterRouteAlias(BacktestContract.ALIAS_LEGACY_SECOND) }
        val aliasLogMark = ctx.logMark()
        val alias = ctx.mainValue { TRouter.buildIntent(BacktestContract.ALIAS_LEGACY_SECOND) }
        ctx.noteNavigateLogs(aliasLogMark, "别名 buildIntent")
        ctx.require(alias is TRouterIntent.Ready, "别名应能产出 Intent，实际=${describeIntent(alias)}")
        ctx.expect(
            (alias as TRouterIntent.Ready).intent.getStringExtra(RouteLaunch.EXTRA_PATH),
            RouterContract.PATH_SECOND,
            "别名产出 Intent 里的 path（应是真实路径）",
        )
    }

    private fun c07() = BacktestNode(
        id = "C07",
        feature = F_C,
        title = "链上需要等待时：buildIntent 明确拒绝，buildIntentAsync 正常产出",
        expected = "异步拦截器会延后放行时：buildIntent 返回 Blocked 并提示改用 buildIntentAsync；buildIntentAsync 能产出可启动的 Intent；两种方式都**只产出、不启动**页面",
    ) { ctx ->
        DemoInterceptors.async.enabled = true
        DemoInterceptors.async.delayMs = 300L
        val mark = ctx.mark()

        val sync = ctx.mainValue { TRouter.buildIntent(RouterContract.PATH_SECOND) }
        ctx.require(sync is TRouterIntent.Blocked, "同步 buildIntent 应被拒绝，实际=${describeIntent(sync)}")
        ctx.expectContains(
            (sync as TRouterIntent.Blocked).reason,
            "buildIntentAsync",
            "拒绝原因里给出替代方案",
        )

        val async = ctx.awaitCallback<TRouterIntent>(10_000, "buildIntentAsync 产出") { cb ->
            TRouter.buildIntentAsync(RouterContract.PATH_SECOND) { cb(it) }
        }
        ctx.require(async is TRouterIntent.Ready, "buildIntentAsync 应产出 Intent，实际=${describeIntent(async)}")
        ctx.expect(
            (async as TRouterIntent.Ready).intent.getStringExtra(RouteLaunch.EXTRA_PATH),
            RouterContract.PATH_SECOND,
            "产出 Intent 里的 path",
        )
        // 关键契约：buildIntent 系列**从不**启动页面
        ctx.expectNoPageOpened(mark, quietMs = 800)
        ctx.note("两次调用都只产出 Intent，屏幕上没有出现任何页面")
    }


    /** 把 [TRouterIntent] 描述成人话（失败时把原因也带出来，而不是只打一个对象地址）。 */
    private fun describeIntent(r: TRouterIntent): String = when (r) {
        is TRouterIntent.Ready -> "Ready(path=${r.meta.path}, component=${r.intent.component?.className})"
        is TRouterIntent.NotFound -> "NotFound(path=${r.path})"
        is TRouterIntent.Blocked -> "Blocked(path=${r.path}, reason=${r.reason})"
        TRouterIntent.NotInitialized -> "NotInitialized"
    }

    /** 把这一小段里库自己打的 navigate 出口日志抓出来当证据（失败时最有用）。 */
    private fun BacktestContext.noteNavigateLogs(mark: Int, label: String) {
        val exit = logsSince(mark).filter { it.contains("[navigate][exit]") || it.contains("[interceptor]") }
        if (exit.isEmpty()) note("$label 期间库日志：无") else exit.forEach { note("$label 库日志：${it.take(200)}") }
    }

    // ------------------------------------------------------------------ 工具

    const val REQ_C01: Int = 7101
    const val REQ_C02: Int = 7102
    const val REQ_C03: Int = 7103
    const val REQ_C04: Int = 7104

    /** 等容器把片段真的装进去（事务是异步提交的，这里轮询而不是猜时间）。 */
    private fun BacktestContext.awaitFragment(container: FragmentActivity): androidx.fragment.app.Fragment? {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val fragment = mainValue { container.supportFragmentManager.fragments.firstOrNull() }
            if (fragment != null) return fragment
            sleep(50)
        }
        return null
    }

    /** 等某个具体页面重新回到前台（从它上面打开的页面返回时用）。 */
    private fun BacktestContext.awaitHostBackOf(page: Activity, timeoutMs: Long = 6_000) {
        val mark = mark()
        val back = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < back) {
            if (currentActivity() === page && !page.isFinishing) return
            sleep(60)
        }
        throw NodeFailure("期望回到 ${page.javaClass.simpleName}，实际停在：${describePagesSince(mark)}")
    }
}
