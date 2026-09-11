package com.demo.trouter.backtest

import android.net.Uri
import android.os.Bundle
import com.demo.trouter.DemoRouteRegistry
import com.demo.trouter.DemoStatsApi
import com.demo.trouter.DemoStatsApiImpl
import com.demo.trouter.DynamicDemoActivity
import com.demo.trouter.feature.demo.SecondActivity
import com.trouter.core.api.DemoParams
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouteTargetKind
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult

/**
 * 回测节点 · 第三批：动态能力。
 *
 * - F 运行时注册路径 / 图谱 / 自检 / 热更导入导出
 * - G 路径别名与外部链接（深链）
 * - H 模块间接口调用与跨进程端点
 *
 * 这一批的共性是"库的表结构会被运行时改动"，因此每个节点都必须**自己收拾干净**
 * （注册了什么就在 cleanup 里注销什么）——否则后面节点的失败会变成"上一步没擦干净"的假象。
 */
internal object BacktestNodesDynamic {

    const val F_F = "F · 动态路由 / 图谱 / 自检 / 热更"
    const val F_G = "G · 路径别名与外部链接"
    const val F_H = "H · 模块间服务与跨进程端点"

    private const val BOGUS_TARGET = "com.demo.trouter.NotARealPageActivity"

    val nodes: List<BacktestNode> = listOf(
        f01(), f02(), f03(), f04(), f05(), f06(), f07(), f08(), f09(),
        g01(), g02(), g03(), g04(), g05(), g06(), g07(),
        h01(), h02(), h03(), h04(),
    )

    // ------------------------------------------------------------------ F

    private fun dynamicMeta(path: String = RouterContract.PATH_DYNAMIC_DEMO): RouteMeta = RouteMeta(
        path = path,
        group = RouterContract.GROUP_DYNAMIC,
        targetClassName = DynamicDemoActivity::class.java.name,
        kind = RouteTargetKind.ACTIVITY,
    )

    private fun f01() = BacktestNode(
        id = "F01",
        feature = F_F,
        title = "运行时注册路径：注册后页面真的能打开",
        expected = "运行时注册 /dynamic-demo（该页面没有 @Route 注解）后导航它，DynamicDemoActivity 真的被打开",
    ) { ctx ->
        ctx.require(TRouter.registerRoute(dynamicMeta()), "运行时注册应成功")
        ctx.onCleanup { TRouter.unregisterRoute(RouterContract.PATH_DYNAMIC_DEMO) }

        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(RouterContract.PATH_DYNAMIC_DEMO) }
        ctx.require(result is TRouterResult.Success, "动态路径应能打开，实际=$result")
        val page = ctx.awaitPage(DynamicDemoActivity::class.java, mark)
        ctx.expect(page.intent.getStringExtra(com.trouter.core.api.RouteLaunch.EXTRA_PATH), RouterContract.PATH_DYNAMIC_DEMO, "页面收到的 path")
        ctx.finishPage(page)
        ctx.awaitHostBack()
    }

    private fun f02() = BacktestNode(
        id = "F02",
        feature = F_F,
        title = "注销动态路径后：立刻变成找不到，且不再打开页面",
        expected = "注册→打开成功→注销→再导航返回 NotFound，屏幕上不出现任何新页面",
    ) { ctx ->
        val meta = dynamicMeta()
        TRouter.registerRoute(meta)
        val firstMark = ctx.mark()
        val first = ctx.mainValue { TRouter.navigate(RouterContract.PATH_DYNAMIC_DEMO) }
        ctx.require(first is TRouterResult.Success, "首次应成功，实际=$first")
        val page = ctx.awaitPage(DynamicDemoActivity::class.java, firstMark)
        ctx.finishPage(page)
        ctx.awaitHostBack()

        ctx.require(TRouter.unregisterRoute(RouterContract.PATH_DYNAMIC_DEMO), "注销应成功")
        val mark = ctx.mark()
        val second = ctx.mainValue { TRouter.navigate(RouterContract.PATH_DYNAMIC_DEMO) }
        ctx.require(second is TRouterResult.NotFound, "注销后应 NotFound，实际=$second")
        ctx.expectNoPageOpened(mark)
    }

    private fun f03() = BacktestNode(
        id = "F03",
        feature = F_F,
        title = "重复注册同一条路径：被拒绝，且原有登记不被覆盖",
        expected = "对已注册的 /second 再注册一个指向别的类的路由，返回 false（拒绝覆盖），/second 仍指向原来的页面类",
    ) { ctx ->
        val before = TRouter.registeredRoutes().first { it.path == RouterContract.PATH_SECOND }
        val registered = TRouter.registerRoute(
            RouteMeta(
                path = RouterContract.PATH_SECOND,
                group = RouterContract.GROUP_DEFAULT,
                targetClassName = BOGUS_TARGET,
                kind = RouteTargetKind.ACTIVITY,
            ),
        )
        ctx.require(!registered, "重复路径应被拒绝（返回 false），实际=$registered")
        val after = TRouter.registeredRoutes().first { it.path == RouterContract.PATH_SECOND }
        ctx.expect(after.targetClassName, before.targetClassName, "冲突后 /second 仍指向的类")
    }

    private fun f04() = BacktestNode(
        id = "F04",
        feature = F_F,
        title = "自检：注册了但类加载不了的路径会被点名",
        expected = "运行时注册一个指向不存在类的路径后，checkRouteTargets() 点名这条路径；注销后自检回到干净",
    ) { ctx ->
        val bogusPath = RouterContract.PATH_DYNAMIC_DEMO
        TRouter.registerRoute(
            RouteMeta(
                path = bogusPath,
                group = RouterContract.GROUP_DYNAMIC,
                targetClassName = BOGUS_TARGET,
                kind = RouteTargetKind.ACTIVITY,
            ),
        )
        ctx.onCleanup { TRouter.unregisterRoute(bogusPath) }

        val missing = TRouter.checkRouteTargets()
        ctx.require(
            missing.any { it.path == bogusPath },
            "自检应点名 $bogusPath，实际点名了 ${missing.map { it.path }}",
        )
        ctx.note("自检点名=${missing.map { it.path }}")

        TRouter.unregisterRoute(bogusPath)
        val clean = TRouter.checkRouteTargets()
        ctx.require(clean.isEmpty(), "注销后自检应干净，实际仍报告 ${clean.map { it.path }}")
    }

    private fun f05() = BacktestNode(
        id = "F05",
        feature = F_F,
        title = "批量热更的原子性：一条不合法，整批不生效",
        expected = "一批里同时有一条合法、一条与既有路径冲突时，applyRouteConfig 返回 false，且两条都没有落地（合法的那条也不该生效）",
    ) { ctx ->
        val goodPath = BacktestContract.DYNAMIC_ATOMIC_GOOD
        val good = RouteMeta(goodPath, RouterContract.GROUP_DYNAMIC, DynamicDemoActivity::class.java.name, RouteTargetKind.ACTIVITY)
        val conflicting = RouteMeta(RouterContract.PATH_SECOND, RouterContract.GROUP_DYNAMIC, BOGUS_TARGET, RouteTargetKind.ACTIVITY)
        val secondBefore = TRouter.registeredRoutes().first { it.path == RouterContract.PATH_SECOND }.targetClassName

        val applied = TRouter.applyRouteConfig(removes = emptyList(), adds = listOf(good, conflicting))
        ctx.require(!applied, "含冲突的一批应整体失败（返回 false），实际=$applied")
        ctx.require(
            TRouter.registeredRoutes().none { it.path == goodPath },
            "整批失败时，合法的那条也不该落地，实际注册表里出现了 $goodPath",
        )
        ctx.expect(
            TRouter.registeredRoutes().first { it.path == RouterContract.PATH_SECOND }.targetClassName,
            secondBefore,
            "冲突后 /second 仍指向的类",
        )
        ctx.note("整批失败后 /second 与 $goodPath 均未被改动")
    }

    private fun f06() = BacktestNode(
        id = "F06",
        feature = F_F,
        title = "动态路由导出：只含动态注册的，静态路由不掺进来",
        expected = "注册一条动态路由后，exportDynamicRoutes() 里只有它；静态的 /second、/about 等不出现在动态集合里",
    ) { ctx ->
        val meta = dynamicMeta()
        TRouter.registerRoute(meta)
        ctx.onCleanup { TRouter.unregisterRoute(RouterContract.PATH_DYNAMIC_DEMO) }

        val dynamic = TRouter.exportDynamicRoutes()
        ctx.expect(dynamic.map { it.path }, listOf(RouterContract.PATH_DYNAMIC_DEMO), "动态集合内容")
        ctx.require(
            dynamic.none { it.path == RouterContract.PATH_SECOND || it.path == RouterContract.PATH_ABOUT },
            "静态路由不该出现在动态集合里，实际=${dynamic.map { it.path }}",
        )
    }

    private fun f07() = BacktestNode(
        id = "F07",
        feature = F_F,
        title = "路由图谱与路由表一致",
        expected = "routeGraph 的边数 = 注册路由数；每个目标类去重后计入节点数；每条边的 path 都能在 registeredRoutes 里找到",
    ) { ctx ->
        val routes = TRouter.registeredRoutes()
        val graph = TRouter.routeGraph()
        ctx.expect(graph.edges.size, routes.size, "图谱边数")
        ctx.expect(graph.nodes.size, routes.map { it.targetClassName }.distinct().size, "图谱节点数（去重后的目标类）")
        val paths = routes.map { it.path }.toSet()
        val orphan = graph.edges.filter { it.path !in paths }.map { it.path }
        ctx.require(orphan.isEmpty(), "图谱里出现了路由表里没有的边：$orphan")
        ctx.note("图谱：节点 ${graph.nodes.size} / 边 ${graph.edges.size}")
    }

    private fun f08() = BacktestNode(
        id = "F08",
        feature = F_F,
        title = "重复装配：不会把路由表撑大，重复路径会被点名",
        expected = "再调一次 install(同一个注册表)：路由条数不变，日志里出现 duplicate 告警（first-wins 语义可见）",
    ) { ctx ->
        val before = TRouter.registeredRoutes().size
        val logMark = ctx.logMark()
        ctx.mainValue {
            TRouter.install(DemoRouteRegistry)
            Unit
        }
        val after = TRouter.registeredRoutes().size
        ctx.expect(after, before, "重复装配后的路由条数")
        val duplicates = ctx.logsSince(logMark).filter { it.contains("[RouteTable][duplicate]") }
        ctx.require(duplicates.isNotEmpty(), "重复装配应留下 duplicate 告警，实际日志=${ctx.logsSince(logMark).takeLast(3)}")
        ctx.note("duplicate 告警 ${duplicates.size} 条，例如：${duplicates.first().take(120)}")
    }

    private fun f09() = BacktestNode(
        id = "F09",
        feature = F_F,
        title = "聚合注册表体检：无重复路径、关键路径在、三个模块都有贡献",
        expected = "registeredRoutes() 无重复 path；/second /about /main /fragment-demo /remote-second 都在；host 与两个 feature 模块的页面都出现在表里",
    ) { ctx ->
        val routes = TRouter.registeredRoutes()
        val duplicated = routes.groupBy { it.path }.filterValues { it.size > 1 }.keys
        ctx.require(duplicated.isEmpty(), "出现了重复路径：$duplicated")

        val required = listOf(
            RouterContract.PATH_MAIN,
            RouterContract.PATH_SECOND,
            RouterContract.PATH_FRAGMENT_DEMO,
            RouterContract.PATH_ABOUT,
            RouterContract.PATH_REMOTE_SECOND,
            BacktestContract.PATH_CONSOLE,
        )
        val paths = routes.map { it.path }
        val absent = required.filter { it !in paths }
        ctx.require(absent.isEmpty(), "关键路径缺失：$absent")

        val classes = routes.map { it.targetClassName }
        ctx.require(classes.any { it.contains("com.demo.trouter.feature.demo.") }, "feature-demo 模块没有贡献任何页面")
        ctx.require(classes.any { it.contains("com.demo.trouter.feature.about.") }, "feature-about 模块没有贡献任何页面")
        ctx.require(classes.any { it.startsWith("com.demo.trouter.") && !it.contains(".feature.") }, "宿主模块没有贡献任何页面")
        ctx.note("共 ${routes.size} 条路径，来自 3 个模块")
    }

    // ------------------------------------------------------------------ G

    private fun g01() = BacktestNode(
        id = "G01",
        feature = F_G,
        title = "精确别名：老链接照样打开新页面",
        expected = "注册别名 /legacy/second → /second 后，导航 /legacy/second 真的打开 SecondActivity",
    ) { ctx ->
        ctx.require(
            TRouter.registerRouteAlias(BacktestContract.ALIAS_LEGACY_SECOND, RouterContract.PATH_SECOND),
            "别名注册应成功",
        )
        ctx.onCleanup { TRouter.unregisterRouteAlias(BacktestContract.ALIAS_LEGACY_SECOND) }

        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(BacktestContract.ALIAS_LEGACY_SECOND) }
        ctx.require(result is TRouterResult.Success, "别名应能打开页面，实际=$result")
        val page = ctx.awaitPage(SecondActivity::class.java, mark)
        ctx.expect(
            page.intent.getStringExtra(com.trouter.core.api.RouteLaunch.EXTRA_PATH),
            RouterContract.PATH_SECOND,
            "别名跳转后页面收到的 path（应是真实路径）",
        )
        ctx.finishPage(page)
        ctx.awaitHostBack()
    }

    private fun g02() = BacktestNode(
        id = "G02",
        feature = F_G,
        title = "正则别名：一批老链接都能落到同一个新页面",
        expected = "注册 regex:/legacy/item/.* → /second 后，导航 /legacy/item/42 真的打开 SecondActivity",
    ) { ctx ->
        ctx.require(
            TRouter.registerRouteAlias(BacktestContract.ALIAS_REGEX_LEGACY_ITEM, RouterContract.PATH_SECOND),
            "正则别名注册应成功",
        )
        ctx.onCleanup { TRouter.unregisterRouteAlias(BacktestContract.ALIAS_REGEX_LEGACY_ITEM) }

        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(BacktestContract.ALIAS_LEGACY_ITEM_42) }
        ctx.require(result is TRouterResult.Success, "正则别名应能打开页面，实际=$result")
        ctx.awaitPage(SecondActivity::class.java, mark)
    }

    private fun g03() = BacktestNode(
        id = "G03",
        feature = F_G,
        title = "别名与真实路径同名：被拒绝（不会制造二义性）",
        expected = "用已注册的真实路径当别名去注册，返回 false",
    ) { ctx ->
        val ok = TRouter.registerRouteAlias(RouterContract.PATH_SECOND, RouterContract.PATH_ABOUT)
        ctx.require(!ok, "与真实路径同名的别名应被拒绝，实际=$ok")
        ctx.onCleanup { TRouter.unregisterRouteAlias(RouterContract.PATH_SECOND) }
        ctx.require(TRouter.registeredRouteAliases().keys.none { it == RouterContract.PATH_SECOND }, "别名表里不该留下它")
    }

    private fun g04() = BacktestNode(
        id = "G04",
        feature = F_G,
        title = "别名成环：跳数上限收口，不会无限解析",
        expected = "两个别名互相指向时，导航返回 Blocked（原因含 alias loop），屏幕上不出现任何页面",
    ) { ctx ->
        ctx.require(
            TRouter.registerRouteAlias(BacktestContract.ALIAS_LOOP_A, BacktestContract.ALIAS_LOOP_B),
            "别名 a 注册应成功",
        )
        ctx.require(
            TRouter.registerRouteAlias(BacktestContract.ALIAS_LOOP_B, BacktestContract.ALIAS_LOOP_A),
            "别名 b 注册应成功",
        )
        ctx.onCleanup {
            TRouter.unregisterRouteAlias(BacktestContract.ALIAS_LOOP_A)
            TRouter.unregisterRouteAlias(BacktestContract.ALIAS_LOOP_B)
        }
        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigate(BacktestContract.ALIAS_LOOP_A) }
        ctx.require(result is TRouterResult.Blocked, "成环应被收口为 Blocked，实际=$result")
        ctx.expectContains((result as TRouterResult.Blocked).reason, "alias loop", "收口原因")
        ctx.expectNoPageOpened(mark)
    }

    private fun g05() = BacktestNode(
        id = "G05",
        feature = F_G,
        title = "外部链接进来：链接里的参数覆盖调用方参数并送达页面",
        expected = "trouter://app/second?msg=from-uri 打开 SecondActivity，页面收到的 msg 是链接里的值（覆盖了 bundle 里的值）",
    ) { ctx ->
        val uri = Uri.parse("trouter://app/second?${DemoParams.KEY_MSG}=from-uri")
        val mark = ctx.mark()
        val bundle = Bundle().apply { putString(DemoParams.KEY_MSG, "来自 bundle 的值") }
        val result = ctx.mainValue { TRouter.navigateUri(uri, bundle) }
        ctx.require(result is TRouterResult.Success, "白名单内的链接应能打开页面，实际=$result")

        val page = ctx.awaitPage(SecondActivity::class.java, mark)
        val args = com.trouter.core.api.RouteArgs.of(page.intent)
        ctx.expect(args.str(DemoParams.KEY_MSG), "from-uri", "页面真实收到的 msg（链接应覆盖 bundle）")
        ctx.note("链接携带的数字参数会以字符串形式送达；页面若用 getInt 读取会拿到默认值（已在报告里记录）")

        val mark2 = ctx.mark()
        val withNumber = ctx.mainValue {
            TRouter.navigateUri(Uri.parse("trouter://app/second?${DemoParams.KEY_MSG}=uri2&${DemoParams.KEY_COUNT}=7"))
        }
        ctx.require(withNumber is TRouterResult.Success, "带数字参数的链接也应能打开，实际=$withNumber")
        val page2 = ctx.awaitPage(SecondActivity::class.java, mark2)
        ctx.note(
            "链接里 count=7 送达后，页面用 RouteArgs.int 读到的是 " +
                "${com.trouter.core.api.RouteArgs.of(page2.intent).int(DemoParams.KEY_COUNT, -1)}" +
                "（原始类型是 ${page2.intent.extras?.get(DemoParams.KEY_COUNT)?.javaClass?.simpleName}）",
        )
    }

    private fun g06() = BacktestNode(
        id = "G06",
        feature = F_G,
        title = "外部链接的负向情形：白名单外的 scheme、缺路径都要明确报错",
        expected = "scheme 不在白名单内 → Blocked 且原因提到 scheme；链接没有路径段 → Blocked 且原因提到缺少路径；两种情况都不打开页面",
    ) { ctx ->
        val wrongScheme = ctx.mainValue { TRouter.navigateUri(Uri.parse("http://app/second")) }
        ctx.require(wrongScheme is TRouterResult.Blocked, "白名单外 scheme 应被拒绝，实际=$wrongScheme")
        ctx.expectContains((wrongScheme as TRouterResult.Blocked).reason, "scheme", "拒绝原因")

        val mark = ctx.mark()
        val noPath = ctx.mainValue { TRouter.navigateUri(Uri.parse("trouter://app")) }
        ctx.require(noPath is TRouterResult.Blocked, "缺少路径段应被拒绝，实际=$noPath")
        ctx.expectContains((noPath as TRouterResult.Blocked).reason, "缺少路径", "拒绝原因")
        ctx.expectNoPageOpened(mark)
    }

    private fun g07() = BacktestNode(
        id = "G07",
        feature = F_G,
        title = "外部链接的 scheme 大小写：不应该影响匹配",
        expected = "系统对 scheme 的匹配本身不区分大小写（manifest 里注册 router://，收到 Router:// 也会投递到 App）；因此 TROUTER://app/second 这种写法同样应该能打开页面",
    ) { ctx ->
        val mark = ctx.mark()
        val result = ctx.mainValue { TRouter.navigateUri(Uri.parse("TROUTER://app/second")) }
        ctx.note("大写 scheme 的结果=$result")
        ctx.require(
            result is TRouterResult.Success,
            "大写 scheme 被拒绝了（$result）——外部来源的链接大小写不受 App 控制，这会让合法链接打不开",
        )
        ctx.awaitPage(SecondActivity::class.java, mark)
    }

    // ------------------------------------------------------------------ H

    private fun h01() = BacktestNode(
        id = "H01",
        feature = F_H,
        title = "模块间接口调用：注册、查找、拒重复、注销",
        expected = "注册实现后 findService 拿到的就是同一个实例；重复注册同一接口返回 false；注销后 findService 返回 null",
    ) { ctx ->
        val impl = DemoStatsApiImpl()
        ctx.require(TRouter.registerService(DemoStatsApi::class.java, impl), "首次注册应成功")
        ctx.onCleanup { TRouter.unregisterService(DemoStatsApi::class.java) }

        ctx.require(TRouter.findService(DemoStatsApi::class.java) === impl, "findService 应返回同一个实例")
        ctx.require(!TRouter.registerService(DemoStatsApi::class.java, DemoStatsApiImpl()), "重复注册应被拒绝")
        ctx.require(TRouter.unregisterService(DemoStatsApi::class.java), "注销应成功")
        ctx.require(TRouter.findService(DemoStatsApi::class.java) == null, "注销后应查不到")
    }

    private fun h02() = BacktestNode(
        id = "H02",
        feature = F_H,
        title = "已注册服务快照：登记的东西看得见",
        expected = "注册两个接口后，registeredServices() 里能看到这两个接口；注销后快照里不再有",
    ) { ctx ->
        val stats = DemoStatsApiImpl()
        val marker = Runnable { }
        ctx.require(TRouter.registerService(DemoStatsApi::class.java, stats), "注册 DemoStatsApi 应成功")
        ctx.require(TRouter.registerService(Runnable::class.java, marker), "注册 Runnable 应成功")
        ctx.onCleanup {
            TRouter.unregisterService(DemoStatsApi::class.java)
            TRouter.unregisterService(Runnable::class.java)
        }
        val snapshot = TRouter.registeredServices()
        ctx.require(snapshot.contains(DemoStatsApi::class.java), "快照里应有 DemoStatsApi，实际=$snapshot")
        ctx.require(snapshot.contains(Runnable::class.java), "快照里应有 Runnable，实际=$snapshot")

        TRouter.unregisterService(Runnable::class.java)
        ctx.require(
            !TRouter.registeredServices().contains(Runnable::class.java),
            "注销后快照里不该还有 Runnable",
        )
    }

    private fun h03() = BacktestNode(
        id = "H03",
        feature = F_H,
        title = "跨进程端点的本地行为：调用、未注册错误、错误识别",
        expected = "本地注册端点后被调用返回预期字符串；调用没注册的端点返回 -ERR 开通的错误串，且 isRemoteEndpointError 认得出来",
    ) { ctx ->
        val name = "btEcho"
        ctx.require(TRouter.registerRemoteEndpoint(name) { args -> "echo:${args.getString("q")}" }, "端点注册应成功")
        ctx.onCleanup { TRouter.unregisterRemoteEndpoint(name) }

        val reply = TRouter.invokeRemoteEndpoint(name, Bundle().apply { putString("q", "hello") })
        ctx.expect(reply, "echo:hello", "端点返回值")
        ctx.require(!TRouter.isRemoteEndpointError(reply), "正常回包不该被判为错误：$reply")

        val missing = TRouter.invokeRemoteEndpoint("btMissingEndpoint", Bundle())
        ctx.note("未注册端点回包=$missing")
        ctx.require(TRouter.isRemoteEndpointError(missing), "未注册端点的回包应被判为错误：$missing")
        ctx.expectContains(missing, "unregistered", "未注册回包内容")
    }

    private fun h04() = BacktestNode(
        id = "H04",
        feature = F_H,
        title = "端点重名：被拒绝，不会悄悄顶掉原来的实现",
        expected = "同名端点重复注册返回 false，且第一次注册的实现仍然生效",
    ) { ctx ->
        val name = "btDup"
        ctx.require(TRouter.registerRemoteEndpoint(name) { "first" }, "首次注册应成功")
        ctx.onCleanup { TRouter.unregisterRemoteEndpoint(name) }

        val again = TRouter.registerRemoteEndpoint(name) { "second" }
        ctx.require(!again, "重名注册应被拒绝，实际=$again")
        ctx.expect(TRouter.invokeRemoteEndpoint(name, Bundle()), "first", "重名后实际生效的端点实现")
    }
}
