package com.demo.trouter.backtest

import android.os.Bundle
import android.os.Looper
import com.demo.trouter.DemoInterceptors
import com.demo.trouter.feature.demo.MockSecondActivity
import com.demo.trouter.feature.demo.SecondActivity
import com.trouter.core.api.AsyncChain
import com.trouter.core.api.AsyncInterceptor
import com.trouter.core.api.ChainOutcome
import com.trouter.core.api.InterceptorChain
import com.trouter.core.api.InterceptorDecision
import com.trouter.core.api.RouteInterceptor
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult
import com.trouter.core.api.WrappingInterceptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 回测节点 · 第二批：拦截链。
 *
 * - D 拦截器：拦下 / 改道 / 优先级 / 洋葱包裹 / 目标级挂载 / 运行中增删 / 拦截器自己出错
 * - E 异步拦截器：延后放行 / 超时 / 取消 / 改道 / 重复终止 / 与同步导航的兼容边界
 *
 * 这一批最容易被"单元测试通过"骗过去：返回值对了不等于页面没被偷偷打开。
 * 因此每个节点都同时验两件事——**结果是什么** + **屏幕上到底发生了什么**。
 */
internal object BacktestNodesChain {

    const val F_D = "D · 拦截器（拦截/改道/优先级/包裹）"
    const val F_E = "E · 异步拦截器（等待/超时/取消）"

    val nodes: List<BacktestNode> = listOf(
        d01(), d02(), d03(), d04(), d05(), d06(), d07(), d08(),
        e01(), e02(), e03(), e04(), e05(), e06(), e07(), e08(),
    )

    // ------------------------------------------------------------------ D

    private fun d01() = BacktestNode(
        id = "D01",
        feature = F_D,
        title = "门禁拦截器：拦下的跳转不会打开页面",
        expected = "开启门禁后导航 /second 返回 Blocked（原因含门禁提示），屏幕上不出现任何新页面；关闭后恢复正常",
    ) { ctx ->
        DemoInterceptors.gate.enabled = true
        val logMark = ctx.logMark()
        val mark = ctx.mark()

        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
        ctx.require(result is TRouterResult.Blocked, "应被拦下（Blocked），实际=$result")
        val reason = (result as TRouterResult.Blocked).reason
        ctx.note("Blocked 原因=$reason")
        ctx.expectContains(reason, "门禁", "拦截原因")
        ctx.expectNoPageOpened(mark)
        ctx.requireLog(logMark, "result=Blocked", "拦截出口日志")

        DemoInterceptors.gate.enabled = false
        val mark2 = ctx.mark()
        val ok = ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
        ctx.require(ok is TRouterResult.Success, "关闭门禁后应放行，实际=$ok")
        ctx.awaitPage(SecondActivity::class.java, mark2)
    }

    private fun d02() = BacktestNode(
        id = "D02",
        feature = F_D,
        title = "改道拦截器：请求 /second，屏幕上出现的是替身页",
        expected = "开启 Mock 后导航 /second，真正被打开的是 MockSecondActivity（/mock/second），真实页不出现",
    ) { ctx ->
        DemoInterceptors.mock.enabled = true
        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
        ctx.require(result is TRouterResult.Success, "改道后应成功打开替身页，实际=$result")
        ctx.expect((result as TRouterResult.Success).meta.path, RouterContract.PATH_MOCK_SECOND, "最终打开的 path")

        val page = ctx.awaitPage(MockSecondActivity::class.java, mark)
        ctx.expect(ctx.countPagesSince(SecondActivity::class.java, mark), 0, "真实页被打开的次数")
        ctx.finishPage(page)
        ctx.awaitHostBack()
    }

    private fun d03() = BacktestNode(
        id = "D03",
        feature = F_D,
        title = "改道成环：跳数上限收口，不会无限循环",
        expected = "两个路径互相改道时，导航最终返回 Blocked（原因含 redirect loop），屏幕上不出现任何页面",
    ) { ctx ->
        val looper = object : RouteInterceptor {
            override fun intercept(meta: RouteMeta, bundle: Bundle?): InterceptorDecision =
                when (meta.path) {
                    RouterContract.PATH_SECOND -> InterceptorDecision.Redirect(RouterContract.PATH_MOCK_SECOND)
                    RouterContract.PATH_MOCK_SECOND -> InterceptorDecision.Redirect(RouterContract.PATH_SECOND)
                    else -> InterceptorDecision.Continue
                }
        }
        ctx.addInterceptor(looper)
        val mark = ctx.mark()
        val logMark = ctx.logMark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
        ctx.require(result is TRouterResult.Blocked, "成环应被收口为 Blocked，实际=$result")
        ctx.expectContains((result as TRouterResult.Blocked).reason, "redirect loop", "收口原因")
        ctx.expectNoPageOpened(mark)
        ctx.note("日志里出现的跳数=${ctx.logsSince(logMark).count { it.contains("result=Redirect") }}")
    }

    private fun d04() = BacktestNode(
        id = "D04",
        feature = F_D,
        title = "拦截器优先级：优先级大的先执行",
        expected = "注册两个优先级不同的拦截器，执行顺序为 高优先级 → 低优先级（与声明顺序相反）",
    ) { ctx ->
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val high = object : RouteInterceptor {
            override val priority: Int = 10
            override fun intercept(meta: RouteMeta, bundle: Bundle?): InterceptorDecision {
                order.add("high")
                return InterceptorDecision.Continue
            }
        }
        val low = object : RouteInterceptor {
            override val priority: Int = 5
            override fun intercept(meta: RouteMeta, bundle: Bundle?): InterceptorDecision {
                order.add("low")
                return InterceptorDecision.Continue
            }
        }
        ctx.addInterceptor(low)
        ctx.addInterceptor(high)

        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
        ctx.require(result is TRouterResult.Success, "应放行并打开页面，实际=$result")
        ctx.awaitPage(SecondActivity::class.java, mark)
        ctx.expect(order.toList(), listOf("high", "low"), "拦截器执行顺序")
    }

    private fun d05() = BacktestNode(
        id = "D05",
        feature = F_D,
        title = "洋葱包裹拦截器：放行前、放行后都执行到了，页面确实打开",
        expected = "包裹拦截器的前后置逻辑都执行（顺序：前置→打开页面→后置），页面被真实打开",
    ) { ctx ->
        val trace = java.util.Collections.synchronizedList(ArrayList<String>())
        val wrapping = object : WrappingInterceptor {
            override fun intercept(chain: InterceptorChain): ChainOutcome {
                trace.add("前置")
                val outcome = chain.proceed()
                trace.add("后置：${outcome.javaClass.simpleName}")
                return outcome
            }
        }
        ctx.addInterceptor(wrapping)

        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
        ctx.require(result is TRouterResult.Success, "应放行并打开页面，实际=$result")
        ctx.awaitPage(SecondActivity::class.java, mark)
        ctx.expect(trace.size, 2, "包裹逻辑执行条数")
        ctx.expect(trace[0], "前置", "第一条")
        ctx.expectContains(trace[1], "后置", "第二条")
        ctx.note("包裹轨迹=${trace.toList()}")
    }

    private fun d06() = BacktestNode(
        id = "D06",
        feature = F_D,
        title = "洋葱包裹拦截器直接拦下：页面不打开",
        expected = "包裹拦截器不调用 proceed() 而是返回 Blocked，导航返回 Blocked，屏幕上不出现任何页面",
    ) { ctx ->
        val blocking = object : WrappingInterceptor {
            override fun intercept(chain: InterceptorChain): ChainOutcome =
                ChainOutcome.Blocked(chain.meta.path, "D06 包裹拦截器拦下")
        }
        ctx.addInterceptor(blocking)
        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
        ctx.require(result is TRouterResult.Blocked, "应被拦下，实际=$result")
        ctx.expectContains((result as TRouterResult.Blocked).reason, "D06 包裹拦截器拦下", "拦截原因")
        ctx.expectNoPageOpened(mark)
    }

    private fun d07() = BacktestNode(
        id = "D07",
        feature = F_D,
        title = "目标级拦截器没绑定：明确报错而不是静默跳过",
        expected = "解绑 /remote-second 上声明的 remoteAudit 后导航它，返回 Blocked 且原因指出目标拦截器未注册；不应静默放行",
    ) { ctx ->
        val rebound = TRouter.unbindTargetInterceptor("remoteAudit")
        ctx.note("解绑 remoteAudit=$rebound")
        ctx.onCleanup { bindRemoteAudit() }

        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_REMOTE_SECOND) }
        ctx.require(result is TRouterResult.Blocked, "缺绑定应 Blocked，实际=$result")
        ctx.expectContains((result as TRouterResult.Blocked).reason, "目标拦截器未注册", "拦截原因")
        ctx.expectNoPageOpened(mark)
    }

    private fun d08() = BacktestNode(
        id = "D08",
        feature = F_D,
        title = "拦截器自己抛异常：变成可读的 Blocked，不崩溃、不开页",
        expected = "拦截器抛出的异常被收口成 Blocked（原因含 interceptor error 与异常类型），屏幕上不出现任何页面，进程不崩溃",
    ) { ctx ->
        val boom = object : RouteInterceptor {
            override fun intercept(meta: RouteMeta, bundle: Bundle?): InterceptorDecision {
                throw IllegalStateException("D08 拦截器故意抛出的异常")
            }
        }
        ctx.addInterceptor(boom)
        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
        ctx.require(result is TRouterResult.Blocked, "应被收口为 Blocked，实际=$result")
        val reason = (result as TRouterResult.Blocked).reason
        ctx.expectContains(reason, "interceptor error", "收口原因")
        ctx.expectContains(reason, "IllegalStateException", "收口原因")
        ctx.expectNoPageOpened(mark)
    }

    // ------------------------------------------------------------------ E

    private fun e01() = BacktestNode(
        id = "E01",
        feature = F_E,
        title = "异步拦截器延后放行：页面打开，回调在主线程且只回调一次",
        expected = "开启异步拦截器（延时 300ms < 超时 1500ms）后用 navigateAsync，页面被真实打开；结果回调发生在主线程，且只回调一次",
    ) { ctx ->
        DemoInterceptors.async.enabled = true
        DemoInterceptors.async.delayMs = 300L
        val callbacks = AtomicInteger(0)
        var onMain = false
        val mark = ctx.mark()

        val result = ctx.awaitCallback<TRouterResult>(8_000, "navigateAsync 回调") { cb ->
            TRouter.navigateAsync(RouterContract.PATH_SECOND) { r ->
                callbacks.incrementAndGet()
                onMain = Looper.myLooper() === Looper.getMainLooper()
                cb(r)
            }
        }
        ctx.require(result is TRouterResult.Success, "异步放行后应 Success，实际=$result")
        ctx.awaitPage(SecondActivity::class.java, mark)
        ctx.expect(callbacks.get(), 1, "结果回调次数")
        ctx.require(onMain, "结果回调应在主线程，实际 onMain=$onMain")
        ctx.note("回调在主线程=true")
    }

    private fun e02() = BacktestNode(
        id = "E02",
        feature = F_E,
        title = "需要等待时用同步导航：明确拒绝，且不卡主线程",
        expected = "异步拦截器会延后放行时，同步 navigate 返回 Blocked（原因提示改用 navigateAsync）；页面不打开；主线程在 300ms 内仍然可响应",
    ) { ctx ->
        DemoInterceptors.async.enabled = true
        DemoInterceptors.async.delayMs = 500L
        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
        ctx.require(result is TRouterResult.Blocked, "同步导航应被拒绝，实际=$result")
        ctx.expectContains((result as TRouterResult.Blocked).reason, "navigateAsync", "拒绝原因")
        ctx.require(ctx.mainThreadResponsiveWithin(300), "主线程被阻塞了（同步导航不应等待异步拦截器）")
        ctx.expectNoPageOpened(mark)
    }

    private fun e03() = BacktestNode(
        id = "E03",
        feature = F_E,
        title = "异步拦截器立即放行：同步导航照常可用（兼容边界）",
        expected = "异步拦截器开关关闭时会立即放行，此时同步 navigate 正常打开页面，不受影响",
    ) { ctx ->
        DemoInterceptors.async.enabled = false
        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
        ctx.require(result is TRouterResult.Success, "立即放行时应正常打开，实际=$result")
        ctx.awaitPage(SecondActivity::class.java, mark)
    }

    private fun e04() = BacktestNode(
        id = "E04",
        feature = F_E,
        title = "异步拦截器一直不回来：超时收口，迟到的放行不再开页",
        expected = "异步耗时 3000ms 超过配置超时 1500ms：结果 Blocked（原因含超时）；再等 2 秒（迟到的放行到达），页面依然没有打开",
    ) { ctx ->
        DemoInterceptors.async.enabled = true
        DemoInterceptors.async.delayMs = 3_000L
        val mark = ctx.mark()
        val started = android.os.SystemClock.elapsedRealtime()

        val result = ctx.awaitCallback<TRouterResult>(8_000, "超时收口") { cb ->
            TRouter.navigateAsync(RouterContract.PATH_SECOND) { cb(it) }
        }
        val cost = android.os.SystemClock.elapsedRealtime() - started
        ctx.require(result is TRouterResult.Blocked, "应超时收口为 Blocked，实际=$result")
        ctx.expectContains((result as TRouterResult.Blocked).reason, "超时", "收口原因")
        ctx.note("收口耗时=${cost}ms（配置超时 1500ms）")
        ctx.require(cost < 2_900, "收口耗时 $cost ms 不应等到异步拦截器自己返回（3000ms）")

        ctx.sleep(2_000) // 等迟到的 proceed 到达
        ctx.expectNoPageOpened(mark, quietMs = 500)
        ctx.note("迟到的放行没有打开任何页面")
    }

    private fun e05() = BacktestNode(
        id = "E05",
        feature = F_E,
        title = "导航中途取消：页面永远不打开",
        expected = "异步拦截器还在等待时取消导航：结果 Blocked（原因含取消）；之后即使拦截器放行，页面也不会打开",
    ) { ctx ->
        DemoInterceptors.async.enabled = true
        DemoInterceptors.async.delayMs = 1_200L
        val mark = ctx.mark()
        val latch = CountDownLatch(1)
        var result: TRouterResult? = null

        val request = ctx.mainValue {
            TRouter.navigateAsync(RouterContract.PATH_SECOND) { r -> result = r; latch.countDown() }
        }
        ctx.sleep(150)
        ctx.main { request.cancel() }
        ctx.require(latch.await(8, TimeUnit.SECONDS), "取消后应立刻收到结果回调")
        ctx.require(result is TRouterResult.Blocked, "取消应返回 Blocked，实际=$result")
        ctx.expectContains((result as TRouterResult.Blocked).reason, "取消", "取消原因")

        ctx.sleep(1_500) // 等拦截器原本的延时放行到达
        ctx.expectNoPageOpened(mark, quietMs = 400)
        ctx.note("取消后迟到放行没有打开页面")
    }

    private fun e06() = BacktestNode(
        id = "E06",
        feature = F_E,
        title = "异步拦截器改道：屏幕上出现的是改道后的页面",
        expected = "异步拦截器立即改道 /second → /mock/second，真正被打开的是 MockSecondActivity",
    ) { ctx ->
        val redirector = object : AsyncInterceptor {
            override fun intercept(chain: AsyncChain) {
                if (chain.meta.path == RouterContract.PATH_SECOND) {
                    chain.redirect(RouterContract.PATH_MOCK_SECOND)
                } else {
                    chain.proceed()
                }
            }
        }
        ctx.addInterceptor(redirector)
        val mark = ctx.mark()
        val result = ctx.awaitCallback<TRouterResult>(8_000, "异步改道结果") { cb ->
            TRouter.navigateAsync(RouterContract.PATH_SECOND) { cb(it) }
        }
        ctx.require(result is TRouterResult.Success, "改道后应 Success，实际=$result")
        ctx.expect((result as TRouterResult.Success).meta.path, RouterContract.PATH_MOCK_SECOND, "最终打开的 path")
        ctx.awaitPage(MockSecondActivity::class.java, mark)
        ctx.expect(ctx.countPagesSince(SecondActivity::class.java, mark), 0, "真实页被打开的次数")
    }

    private fun e07() = BacktestNode(
        id = "E07",
        feature = F_E,
        title = "异步拦截器直接拦下：原因如实回传，页面不打开",
        expected = "异步拦截器 block('风控未通过') 后，结果 Blocked 且原因就是这句话；屏幕上不出现任何页面",
    ) { ctx ->
        val blocker = object : AsyncInterceptor {
            override fun intercept(chain: AsyncChain) {
                chain.block("E07 风控未通过")
            }
        }
        ctx.addInterceptor(blocker)
        val mark = ctx.mark()
        val result = ctx.awaitCallback<TRouterResult>(8_000, "异步拦截结果") { cb ->
            TRouter.navigateAsync(RouterContract.PATH_SECOND) { cb(it) }
        }
        ctx.require(result is TRouterResult.Blocked, "应被拦下，实际=$result")
        ctx.expect((result as TRouterResult.Blocked).reason, "E07 风控未通过", "拦截原因")
        ctx.expectNoPageOpened(mark)
    }

    private fun e08() = BacktestNode(
        id = "E08",
        feature = F_E,
        title = "同一轮重复终止：第二次被拒绝，且不会重复打开页面",
        expected = "异步拦截器先放行再试图再次终止时，第二次调用被拒绝（业务侧能观察到 IllegalStateException）；页面只会被打开一次",
    ) { ctx ->
        val observed = java.util.Collections.synchronizedList(ArrayList<String>())
        val tester = object : AsyncInterceptor {
            override fun intercept(chain: AsyncChain) {
                chain.proceed { }
                // 此时剩余链上还有一个"永不自己终止"的异步拦截器，整链尚未收口，
                // 因此这里能真正触发"重复终止"的守卫
                try {
                    chain.block("E08 第二次终止")
                    observed.add("第二次终止被静默接受（未抛错）")
                } catch (e: IllegalStateException) {
                    observed.add("第二次终止被拒绝：${e.javaClass.simpleName}")
                }
            }
        }
        val neverTerminates = object : AsyncInterceptor {
            override fun intercept(chain: AsyncChain) {
                // 故意什么都不做：让整链悬在那里，等超时收口
            }
        }
        ctx.addInterceptor(tester)
        ctx.addInterceptor(neverTerminates)

        val mark = ctx.mark()
        val result = ctx.awaitCallback<TRouterResult>(8_000, "重复终止场景收口") { cb ->
            TRouter.navigateAsync(RouterContract.PATH_SECOND) { cb(it) }
        }
        ctx.expect(observed.toList(), listOf("第二次终止被拒绝：IllegalStateException"), "重复终止的观测结果")
        ctx.require(result is TRouterResult.Blocked, "整链最终应由超时收口为 Blocked，实际=$result")
        ctx.expectNoPageOpened(mark)
        ctx.note("最终结果=${(result as TRouterResult.Blocked).reason}")
    }

    /** 恢复 app 装配时给 remoteAudit 绑定的 no-op 观察者（回测节点不改变 App 的既有行为）。 */
    fun bindRemoteAudit() {
        TRouter.bindTargetInterceptor(
            "remoteAudit",
            object : WrappingInterceptor {
                override fun intercept(chain: InterceptorChain): ChainOutcome = chain.proceed()
            },
        )
    }
}
