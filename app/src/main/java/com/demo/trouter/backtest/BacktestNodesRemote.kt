package com.demo.trouter.backtest

import android.os.Bundle
import android.os.Process
import com.demo.trouter.DemoInner
import com.demo.trouter.DemoLevel
import com.demo.trouter.DemoReport
import com.demo.trouter.DemoStatsApi
import com.demo.trouter.TRouterDemoApp
import com.demo.trouter.feature.about.AboutActivity
import com.demo.trouter.feature.demo.SecondActivity
import com.demo.trouter.generated.TRouterPojo_DemoReport
import com.trouter.core.api.DemoParams
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouteTargetKind
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 回测节点 · 第四批：跨进程与健壮性。
 *
 * - I 跨进程：真的起了别的进程、参数真的过了进程边界、每个进程的端点表/接口表互不串台
 * - J 健壮性：空路径、注册了却打不开、后台线程发起、连发、注销后重装
 *
 * 跨进程的证据分两层：**返回值**（远端 TRouter 说它打开了）+ **远端进程自己的证词**
 * （`demoLastOpen` 端点回读远端页面真实收到的路径与参数）。
 * 只有两层对上，才敢说"参数真的跨进程送达了页面"。
 */
internal object BacktestNodesRemote {

    const val F_I = "I · 跨进程（三进程）"
    const val F_J = "J · 健壮性与边界"

    private const val TARGET_REMOTE2 = TRouterDemoApp.REMOTE_TARGET_SECOND

    val nodes: List<BacktestNode> = listOf(
        i01(), i02(), i03(), i04(), i05(), i06(), i07(), i08(), i09(),
        j01(), j02(), j03(), j04(), j05(), j06(), j07(), j08(),
    )

    // ------------------------------------------------------------------ I

    private fun i01() = BacktestNode(
        id = "I01",
        feature = F_I,
        title = "跨进程打开页面：另一个进程真的开了页面（有它的进程号作证）",
        expected = "导航 /remote-second 返回 Success；随后向 :remote 进程回读它最近打开的页面，路径一致，且进程号与回测台所在进程不同",
    ) { ctx ->
        val result = ctx.awaitCallback<TRouterResult>(15_000, "跨进程导航结果") { cb ->
            TRouter.navigateRemote(RouterContract.PATH_REMOTE_SECOND, null, null) { cb(it) }
        }
        ctx.require(result is TRouterResult.Success, "跨进程导航应 Success，实际=$result")

        // 远端页面是"远端 TRouter 返回之后"才由系统创建的，稍等一下再回读它的自述
        ctx.sleep(600)
        val testimony = ctx.awaitCallback<String>(15_000, "远端进程证词") { cb ->
            TRouter.callRemoteService("demoLastOpen", Bundle(), null) { cb(it) }
        }
        ctx.note("远端证词=$testimony")
        ctx.expectContains(testimony, "path=${RouterContract.PATH_REMOTE_SECOND}", "远端页面真实收到的路径")
        val remotePid = Regex("pid=(-?\\d+)").find(testimony)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        ctx.require(remotePid > 0, "证词里应带远端进程号，实际=$testimony")
        ctx.require(
            remotePid != Process.myPid(),
            "远端进程号($remotePid)与回测台进程号(${Process.myPid()})相同，说明并没有真的跨进程",
        )
        ctx.note("回测台 pid=${Process.myPid()}，远端 pid=$remotePid")
    }

    private fun i02() = BacktestNode(
        id = "I02",
        feature = F_I,
        title = "跨进程带参数：远端页面真的收到了参数",
        expected = "跨进程导航时带 msg/count，远端页面自己记下的收参正是这两个值",
    ) { ctx ->
        val bundle = Bundle().apply {
            putString(DemoParams.KEY_MSG, "I02 跨进程参数")
            putInt(DemoParams.KEY_COUNT, 88)
        }
        val result = ctx.awaitCallback<TRouterResult>(15_000, "跨进程导航结果") { cb ->
            TRouter.navigateRemote(RouterContract.PATH_REMOTE_SECOND, bundle, null) { cb(it) }
        }
        ctx.require(result is TRouterResult.Success, "跨进程导航应 Success，实际=$result")

        // 远端页面是"远端 TRouter 返回之后"才由系统创建的，稍等一下再回读它的自述
        ctx.sleep(600)
        val testimony = ctx.awaitCallback<String>(15_000, "远端进程证词") { cb ->
            TRouter.callRemoteService("demoLastOpen", Bundle(), null) { cb(it) }
        }
        ctx.note("远端证词=$testimony")
        ctx.expectContains(testimony, "msg=I02 跨进程参数", "远端页面真实收到的 msg")
        ctx.expectContains(testimony, "count=88", "远端页面真实收到的 count")
    }

    private fun i03() = BacktestNode(
        id = "I03",
        feature = F_I,
        title = "没标 @CrossProcess 的路径走跨进程通道：被白名单拦下",
        expected = "导航 /second（未标注 @CrossProcess）时返回 Blocked，原因指出未标注 @CrossProcess",
    ) { ctx ->
        val result = ctx.awaitCallback<TRouterResult>(10_000, "跨进程导航结果") { cb ->
            TRouter.navigateRemote(RouterContract.PATH_SECOND, null, null) { cb(it) }
        }
        ctx.require(result is TRouterResult.Blocked, "白名单外应 Blocked，实际=$result")
        ctx.expectContains((result as TRouterResult.Blocked).reason, "CrossProcess", "拒绝原因")
    }

    private fun i04() = BacktestNode(
        id = "I04",
        feature = F_I,
        title = "两个进程各有一份端点表：互不串台",
        expected = "demoClock2 只在 :remote2 注册：指定 target=remote2 能调到（回包带另一个进程号）；用默认 target 调同一个名字只会得到「未注册」",
    ) { ctx ->
        val args = Bundle().apply { putString("q", "from-backtest") }

        val fromRemote2 = ctx.awaitCallback<String>(15_000, ":remote2 端点回包") { cb ->
            TRouter.callRemoteService("demoClock2", args, TARGET_REMOTE2) { cb(it) }
        }
        ctx.note(":remote2 回包=$fromRemote2")
        ctx.expectContains(fromRemote2, "clock2-v1", ":remote2 端点回包")
        val pid = Regex("pid=(-?\\d+)").find(fromRemote2)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        ctx.require(pid > 0 && pid != Process.myPid(), "回包应带另一个进程的进程号，实际=$fromRemote2")

        val fromDefault = ctx.awaitCallback<String>(15_000, "默认进程端点回包") { cb ->
            TRouter.callRemoteService("demoClock2", args, null) { cb(it) }
        }
        ctx.note("默认(:remote) 回包=$fromDefault")
        ctx.require(
            TRouter.isRemoteEndpointError(fromDefault),
            "默认进程没注册 demoClock2，应返回未注册错误，实际=$fromDefault",
        )
        ctx.expectContains(fromDefault, "unregistered", "默认进程回包")
    }

    private fun i05() = BacktestNode(
        id = "I05",
        feature = F_I,
        title = "跨进程传业务对象（不用手写序列化）",
        expected = "把含 List / 枚举 / 可空嵌套对象的业务对象发到 :remote2，远端解包后逐字段回显一致，且带远端进程号",
    ) { ctx ->
        val report = DemoReport(
            id = "BT-2026",
            count = 7,
            ok = true,
            tags = listOf("alpha", "beta"),
            inner = DemoInner("内层对象", DemoLevel.HIGH),
        )
        val localRoundTrip = TRouterPojo_DemoReport.unpack(
            Bundle().apply { TRouterPojo_DemoReport.pack(this, report) },
        )
        ctx.expect(localRoundTrip, report, "本地 pack/unpack 往返")

        val bundle = Bundle().apply { TRouterPojo_DemoReport.pack(this, report) }
        val reply = ctx.awaitCallback<String>(15_000, "POJO 跨进程回包") { cb ->
            TRouter.callRemoteService("pojoEcho", bundle, TARGET_REMOTE2) { cb(it) }
        }
        ctx.note("远端回包=$reply")
        ctx.expectContains(reply, "id=BT-2026", "远端解包后的 id")
        ctx.expectContains(reply, "count=7", "远端解包后的 count")
        ctx.expectContains(reply, "tags=alpha|beta", "远端解包后的 List 字段")
        ctx.expectContains(reply, "inner=内层对象/HIGH", "远端解包后的嵌套对象与枚举")
    }

    private fun i06() = BacktestNode(
        id = "I06",
        feature = F_I,
        title = "像调本地接口一样调远端接口：三种签名都对",
        expected = "经类型化远程接口连续调用 count / summarize / report，结果逐项正确，summarize 的回包带远端进程号",
    ) { ctx ->
        val api = TRouter.remoteApi(DemoStatsApi::class.java, TARGET_REMOTE2) { err -> ctx.note("意外错误回调=$err") }

        val count = ctx.awaitCallback<Int>(15_000, "count 结果") { cb -> api.count("abcd") { cb(it) } }
        ctx.expect(count, 8, "count(\"abcd\") 的结果")

        val summary = ctx.awaitCallback<String>(15_000, "summarize 结果") { cb ->
            api.summarize("bt", DemoLevel.HIGH, listOf(1, 2, 3)) { cb(it) }
        }
        ctx.note("summarize=$summary")
        ctx.expectContains(summary, "sum=6", "summarize 回包")
        ctx.expectContains(summary, "level=HIGH", "summarize 回包里的枚举参数")
        val pid = Regex("pid=(-?\\d+)").find(summary)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        ctx.require(pid > 0 && pid != Process.myPid(), "summarize 应带远端进程号，实际=$summary")

        val report = ctx.awaitCallback<DemoReport>(15_000, "report 结果") { cb -> api.report("Z") { cb(it) } }
        ctx.expect(report.id, "remote-Z", "report 返回对象的 id")
        ctx.expect(report.count, 99, "report 返回对象的 count")
        ctx.expect(report.inner?.level, DemoLevel.HIGH, "report 返回对象里的枚举字段")
    }

    private fun i07() = BacktestNode(
        id = "I07",
        feature = F_I,
        title = "调远端没实现的接口：走错误回调，不崩不卡",
        expected = "用默认 target（:remote）调用只在 :remote2 实现的类型化接口，错误回调被触发且原因指出未注册",
    ) { ctx ->
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        val api = TRouter.remoteApi(DemoStatsApi::class.java, null) { err ->
            holder[0] = err
            latch.countDown()
        }
        ctx.main { api.count("abcd") { ctx.note("不该成功却成功了：$it") } }

        ctx.require(latch.await(15, TimeUnit.SECONDS), "错误回调没有在 15s 内触发（应走 onError 而不是静默）")
        val error = holder[0] ?: ""
        ctx.note("错误回调=$error")
        ctx.expectContains(error, "未注册", "错误原因")
    }

    private fun i08() = BacktestNode(
        id = "I08",
        feature = F_I,
        title = "指定了不存在的目标进程：明确报错，不静默走默认进程",
        expected = "target 写成没配置过的名字时返回 Blocked，原因提示要把该进程加进配置",
    ) { ctx ->
        val result = ctx.awaitCallback<TRouterResult>(10_000, "未知 target 的结果") { cb ->
            TRouter.navigateRemote(RouterContract.PATH_REMOTE_THIRD, null, "no-such-process") { cb(it) }
        }
        ctx.require(result is TRouterResult.Blocked, "未知 target 应 Blocked，实际=$result")
        ctx.expectContains((result as TRouterResult.Blocked).reason, "未配置", "拒绝原因")
    }

    private fun i09() = BacktestNode(
        id = "I09",
        feature = F_I,
        title = "拿没登记编解码器的接口做远程代理：立刻报配置错误",
        expected = "对一个没有 @RemoteApi 登记的接口调用 remoteApi()，直接抛 IllegalStateException（配置错误必须显性），而不是运行期悄悄失败",
    ) { ctx ->
        val error = runCatching { TRouter.remoteApi(BacktestUnregisteredApi::class.java, TARGET_REMOTE2) }.exceptionOrNull()
        ctx.require(error != null, "未登记编解码器时应抛异常，实际没有抛")
        ctx.expect(error!!.javaClass, IllegalStateException::class.java, "抛出的异常类型")
        ctx.expectContains(error.message ?: "", "未注册", "异常信息")
    }

    // ------------------------------------------------------------------ J

    private fun j01() = BacktestNode(
        id = "J01",
        feature = F_J,
        title = "空路径：明确 NotFound，不崩溃、不开页",
        expected = "导航空字符串返回 NotFound，屏幕上不出现任何页面",
    ) { ctx ->
        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(BacktestContract.PATH_BLANK) }
        ctx.require(result is TRouterResult.NotFound, "空路径应 NotFound，实际=$result")
        ctx.expectNoPageOpened(mark)
    }

    private fun j02() = BacktestNode(
        id = "J02",
        feature = F_J,
        title = "注册了但目标类不存在：运行期降级为 NotFound，不崩溃",
        expected = "动态注册一条指向不存在类的路径后导航它：返回 NotFound、兜底回调触发、屏幕上不出现页面、进程不崩溃",
    ) { ctx ->
        TRouter.registerRoute(
            RouteMeta(
                path = BacktestContract.PATH_BROKEN,
                group = RouterContract.GROUP_DYNAMIC,
                targetClassName = "com.demo.trouter.NoSuchPageActivity",
                kind = RouteTargetKind.ACTIVITY,
            ),
        )
        ctx.onCleanup { TRouter.unregisterRoute(BacktestContract.PATH_BROKEN) }
        ctx.clearLostPaths()
        val logMark = ctx.logMark()
        val mark = ctx.mark()

        val result = ctx.mainValue { TRouter.navigate(BacktestContract.PATH_BROKEN) }
        ctx.require(result is TRouterResult.NotFound, "打不开的目标应降级为 NotFound，实际=$result")
        ctx.expectNoPageOpened(mark)
        ctx.require(
            ctx.lostPathsSnapshot().contains(BacktestContract.PATH_BROKEN),
            "打不开时兜底回调也应触发，实际=${ctx.lostPathsSnapshot()}",
        )
        ctx.requireLog(logMark, "result=Error", "打开失败出口日志")
    }

    private fun j03() = BacktestNode(
        id = "J03",
        feature = F_J,
        title = "在后台线程发起跳转：照样能打开页面",
        expected = "从一个非主线程调用 navigate，页面仍被打开（库不要求调用方在主线程）",
    ) { ctx ->
        val mark = ctx.mark()
        val latch = CountDownLatch(1)
        var result: TRouterResult? = null
        var error: Throwable? = null
        Thread({
            try {
                result = TRouter.navigate(RouterContract.PATH_SECOND)
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }, "bt-bg-navigate").start()

        ctx.require(latch.await(8, TimeUnit.SECONDS), "后台线程导航没有返回")
        ctx.require(error == null, "后台线程导航抛异常：${error?.javaClass?.simpleName}: ${error?.message}")
        ctx.require(result is TRouterResult.Success, "后台线程导航应 Success，实际=$result")
        ctx.awaitPage(SecondActivity::class.java, mark)
    }

    private fun j04() = BacktestNode(
        id = "J04",
        feature = F_J,
        title = "连发多次跳转：每次都真的打开，不丢不串",
        expected = "连续发起 5 次 /second 导航（不等待、不间隔），5 次都返回「成功」，且屏幕上真的依次出现 5 个页面；全部返回后回到回测台",
    ) { ctx ->
        val mark = ctx.mark()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        repeat(5) {
            val r = ctx.mainValue { TRouter.navigate(RouterContract.PATH_SECOND) }
            ctx.require(r is TRouterResult.Success, "第 ${it + 1} 次导航应 Success，实际=$r")
        }
        ctx.note("5 次导航连发耗时 ${android.os.SystemClock.elapsedRealtime() - startedAt}ms（全部当场返回成功）")

        // 页面切换是异步的：等它到位再数，而不是睡固定时间赌。
        // 这里等得比较宽松，因为观察到一个**平台行为**：连发时系统会把 Activity 启动排队，
        // 最后一次启动实测被推迟过 20~30 秒（同一台模拟器、负载高时更明显）。
        // 那是系统的启动队列，不是库的缺陷——库的职责是"每次导航都如实发起并且不丢不串"，
        // 因此断言落在"页面最终全部出现"，同时把耗时记为证据。
        val opened = ctx.awaitPageCount(SecondActivity::class.java, mark, 5, timeoutMs = 30_000)
        ctx.note("5 个页面全部到前台共耗时 ${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
        ctx.expect(opened, 5, "最终出现的页面数")
        ctx.closeAllPagesSince(mark)
        ctx.awaitHostBack()
    }

    private fun j05() = BacktestNode(
        id = "J05",
        feature = F_J,
        title = "注销静态路径后重装：能恢复",
        expected = "注销 /about 后导航它返回 NotFound；再装配一次注册表后，页面又能正常打开",
    ) { ctx ->
        ctx.require(TRouter.unregisterRoute(RouterContract.PATH_ABOUT), "注销 /about 应成功")
        val mark = ctx.mark()
        val gone = ctx.mainValue { TRouter.navigate(RouterContract.PATH_ABOUT) }
        ctx.require(gone is TRouterResult.NotFound, "注销后应 NotFound，实际=$gone")
        ctx.expectNoPageOpened(mark)

        ctx.mainValue { TRouter.install(com.demo.trouter.DemoRouteRegistry); Unit }
        val mark2 = ctx.mark()
        val back = ctx.mainValue { TRouter.navigate(RouterContract.PATH_ABOUT) }
        ctx.require(back is TRouterResult.Success, "重装后应能打开，实际=$back")
        ctx.awaitPage(AboutActivity::class.java, mark2)
    }

    private fun j06() = BacktestNode(
        id = "J06",
        feature = F_J,
        title = "静态路由表自检：没有一条是打不开的",
        expected = "checkRouteTargets() 在干净的路由表上返回空（每条注册路径的目标类都能加载到）",
    ) { ctx ->
        val missing = TRouter.checkRouteTargets()
        ctx.require(
            missing.isEmpty(),
            "自检发现打不开的路径：${missing.map { "${it.path}→${it.targetClassName}" }}",
        )
        ctx.note("检查了 ${TRouter.registeredRoutes().size} 条路径，全部可加载")
    }

    /**
     * 回归节点（回测发现的问题）：**链上挂了"会等待"的异步拦截器时，打不开的目标依然要按打开失败降级**。
     *
     * 修复前的表现：异步拦截器延后放行后的续跑发生在主线程 Handler 里，目标打开抛出的异常没人兜，
     * 直接变成未捕获异常——**App 崩溃**（而不是返回 NotFound 走兜底页）。
     */
    private fun j07() = BacktestNode(
        id = "J07",
        feature = F_J,
        title = "异步等待之后才发现目标打不开：不崩溃、按打开失败降级",
        expected = "链上有一个真的会等待的异步拦截器，等待结束后才发现目标类不存在：结果 NotFound、兜底回调触发、不出现页面、进程不崩溃",
    ) { ctx ->
        registerBrokenRoute(ctx)
        // 真的会等待的异步拦截器：放行发生在延时回调里（这正是崩溃路径）
        val deferring = object : com.trouter.core.api.AsyncInterceptor {
            private val handler = android.os.Handler(android.os.Looper.getMainLooper())
            override fun intercept(chain: com.trouter.core.api.AsyncChain) {
                handler.postDelayed({ chain.proceed { } }, 300L)
            }
        }
        ctx.addInterceptor(deferring)
        ctx.clearLostPaths()
        val logMark = ctx.logMark()
        val mark = ctx.mark()

        val result = ctx.awaitCallback<TRouterResult>(10_000, "异步链上的打开失败收口") { cb ->
            TRouter.navigateAsync(BacktestContract.PATH_BROKEN) { cb(it) }
        }
        ctx.require(result is TRouterResult.NotFound, "打不开的目标应降级为 NotFound，实际=$result")
        ctx.require(
            ctx.lostPathsSnapshot().contains(BacktestContract.PATH_BROKEN),
            "兜底回调也应触发，实际=${ctx.lostPathsSnapshot()}",
        )
        ctx.expectNoPageOpened(mark)
        ctx.requireLog(logMark, "result=Error", "打开失败出口日志")
    }

    /** 回归节点：**包裹拦截器放行之后**才发现目标打不开，同样不能算成"拦截器出错"。 */
    private fun j08() = BacktestNode(
        id = "J08",
        feature = F_J,
        title = "包裹拦截器放行后目标打不开：按打开失败降级，不误报成拦截器错误",
        expected = "洋葱包裹拦截器 proceed() 之后目标类不存在：结果 NotFound（不是 Blocked(interceptor error)）、兜底回调触发、不出现页面",
    ) { ctx ->
        registerBrokenRoute(ctx)
        val wrapping = object : com.trouter.core.api.WrappingInterceptor {
            override fun intercept(chain: com.trouter.core.api.InterceptorChain): com.trouter.core.api.ChainOutcome =
                chain.proceed()
        }
        ctx.addInterceptor(wrapping)
        ctx.clearLostPaths()
        val logMark = ctx.logMark()
        val mark = ctx.mark()

        val result = ctx.mainValue { TRouter.navigate(BacktestContract.PATH_BROKEN) }
        ctx.require(
            result is TRouterResult.NotFound,
            "打不开的目标应降级为 NotFound，实际=$result（被误报成拦截器错误就是这个问题）",
        )
        ctx.require(
            ctx.lostPathsSnapshot().contains(BacktestContract.PATH_BROKEN),
            "兜底回调也应触发，实际=${ctx.lostPathsSnapshot()}",
        )
        ctx.expectNoPageOpened(mark)
        ctx.requireLog(logMark, "result=Error", "打开失败出口日志")
    }

    /** 注册一条"路径在、目标类不在"的动态路由（用完即注销）。 */
    private fun registerBrokenRoute(ctx: BacktestContext) {
        TRouter.registerRoute(
            RouteMeta(
                path = BacktestContract.PATH_BROKEN,
                group = RouterContract.GROUP_DYNAMIC,
                targetClassName = "com.demo.trouter.NoSuchPageActivity",
                kind = RouteTargetKind.ACTIVITY,
            ),
        )
        ctx.onCleanup { TRouter.unregisterRoute(BacktestContract.PATH_BROKEN) }
    }
}

/** I09 用：没有任何 @RemoteApi 登记的接口。 */
interface BacktestUnregisteredApi {
    fun ping(onResult: (String) -> Unit)
}
