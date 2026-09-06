package com.demo.trouter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouteTargetKind
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult
import com.trouter.core.testing.BaseTRouterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 动态路由与图谱契约测试（场景 S13/S14，见 docs/V5.0-动态路由与图谱方案.md §3）。
 *
 * 测试协议（你确认）：本版**只测 V5 新功能**，不跑历史 26 例；统一回测最后执行。
 * 覆盖：注册前 NotFound → 注册后 Success → 注销后 NotFound；重复注册拒绝（冲突）；静态路由可注销；
 * 图谱静态+动态同图一致。
 */
@RunWith(AndroidJUnit4::class)
class TRouterDynamicContractTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    private val dynamicClass: String = DynamicDemoActivity::class.java.name

    private fun dynamicMeta(otherClass: String = dynamicClass) = RouteMeta(
        path = RouterContract.PATH_DYNAMIC_DEMO,
        group = RouterContract.GROUP_DYNAMIC,
        targetClassName = otherClass,
        kind = RouteTargetKind.ACTIVITY,
    )

    /** S13-1（C1/C3）：注册前 NotFound → 注册后 Success → 注销后恢复 NotFound。 */
    @Test
    fun registerNavigateThenUnregister() {
        // 注册前：未注册 → NotFound + onLost
        val before = TRouter.navigate(RouterContract.PATH_DYNAMIC_DEMO)
        assertEquals(TRouterResult.NotFound(RouterContract.PATH_DYNAMIC_DEMO), before)
        assertTrue(lostPaths.contains(RouterContract.PATH_DYNAMIC_DEMO))

        // 注册 → Success（真实打开 DynamicDemoActivity）
        lostPaths.clear()
        assertTrue("首次注册应成功", TRouter.registerRoute(dynamicMeta()))

        val opened = TRouter.navigate(RouterContract.PATH_DYNAMIC_DEMO)
        assertTrue("注册后应可导航: $opened", opened is TRouterResult.Success)
        assertEquals(dynamicClass, (opened as TRouterResult.Success).meta.targetClassName)
        assertTrue("成功导航不应触发 onLost", lostPaths.isEmpty())

        // 注销 → 恢复 NotFound
        assertTrue("注销应成功", TRouter.unregisterRoute(RouterContract.PATH_DYNAMIC_DEMO))
        val after = TRouter.navigate(RouterContract.PATH_DYNAMIC_DEMO)
        assertEquals(TRouterResult.NotFound(RouterContract.PATH_DYNAMIC_DEMO), after)

        val join = logs.lines.joinToString("\n")
        assertTrue("应记录注册日志:\n$join", join.contains("[route][register] path=${RouterContract.PATH_DYNAMIC_DEMO}"))
        assertTrue("应记录注销日志:\n$join", join.contains("[route][unregister] path=${RouterContract.PATH_DYNAMIC_DEMO}"))
    }

    /** S13-2（C2）：与既有 path 冲突（无论静态）→ 拒绝注册 + 冲突日志，first-wins。 */
    @Test
    fun duplicateDynamicRegistrationIsRejected() {
        val ok = TRouter.registerRoute(dynamicMeta())
        assertTrue(ok)
        val clash = TRouter.registerRoute(
            RouteMeta(
                path = RouterContract.PATH_DYNAMIC_DEMO,
                group = RouterContract.GROUP_DYNAMIC,
                targetClassName = "com.demo.trouter.AnotherClashTarget",
                kind = RouteTargetKind.ACTIVITY,
            ),
        )
        assertFalse("重复 path 应被拒绝（不覆盖）", clash)

        val kept = TRouter.registeredRoutes().first { it.path == RouterContract.PATH_DYNAMIC_DEMO }
        assertEquals("应保留先注册者（first-wins）", dynamicClass, kept.targetClassName)

        val join = logs.lines.joinToString("\n")
        assertTrue("应输出冲突日志:\n$join", join.contains("[route][register][conflict] path=${RouterContract.PATH_DYNAMIC_DEMO}"))
    }

    /** S13-3（C1 注销范围）：静态路由也可注销（路径级统一语义），注销后走降级。 */
    @Test
    fun unregisterStaticRouteWorks() {
        assertTrue(TRouter.unregisterRoute(RouterContract.PATH_SECOND))
        val result = TRouter.navigate(RouterContract.PATH_SECOND)
        assertEquals(TRouterResult.NotFound(RouterContract.PATH_SECOND), result)
        assertTrue(lostPaths.contains(RouterContract.PATH_SECOND))
    }

    /** S14（C4）：图谱 = 静态 + 动态同图；节点由边推导、去重、随注册/注销增减。 */
    @Test
    fun graphIncludesStaticAndDynamicAndStaysConsistent() {
        val initial = TRouter.routeGraph()
        val routes = TRouter.registeredRoutes()
        assertEquals("边数 = 已注册路由数", routes.size, initial.edges.size)
        assertEquals("节点数 = 目标类去重数", routes.map { it.targetClassName }.toSet().size, initial.nodes.size)

        // 注册动态 → 图谱出现对应边与节点
        TRouter.registerRoute(dynamicMeta())
        val withDynamic = TRouter.routeGraph()
        assertTrue(
            "图谱应含动态边: ${withDynamic.edges}",
            withDynamic.edges.any { it.path == RouterContract.PATH_DYNAMIC_DEMO && it.toClass == dynamicClass },
        )
        assertTrue("图谱节点应含动态目标类", withDynamic.nodes.contains(dynamicClass))
        assertTrue("图谱应含静态类（如 Second）", withDynamic.nodes.contains("com.demo.trouter.feature.demo.SecondActivity"))

        // 注销动态 → 边与（无其他引用时的）节点消失
        TRouter.unregisterRoute(RouterContract.PATH_DYNAMIC_DEMO)
        val after = TRouter.routeGraph()
        assertFalse(after.edges.any { it.path == RouterContract.PATH_DYNAMIC_DEMO })
        assertFalse("无该类的其他边时应不再成为节点", after.nodes.contains(dynamicClass))
    }
}
