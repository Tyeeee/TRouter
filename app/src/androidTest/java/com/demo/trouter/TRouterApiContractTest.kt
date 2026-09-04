package com.demo.trouter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.GroupLoader
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouteTargetKind
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult
import com.trouter.core.testing.BaseTRouterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TRouter API 契约测试（场景 S07，见 docs/V1.0-测试与演示台规划.md §2.1）。
 *
 * 说明：UI 行为驱动场景（S01–S05）由 MainRouterTest 覆盖；本类只针对**无法/不宜用 UI 驱动**的
 * 契约分支（S07）做直接 API 验证（NotInitialized、重复 install 幂等、traceId 一致性、NotFound/onLost），
 * 仍继承 BaseTRouterTest 保持统一配置与前后隔离。所有断言基于对外契约而非内部表结构。
 */
@RunWith(AndroidJUnit4::class)
class TRouterApiContractTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    /** 未初始化即导航：返回 NotInitialized（密封类无 null、不崩溃、无日志）。 */
    @Test
    fun navigateBeforeInitReturnsNotInitialized() {
        resetRouterState()
        val result = TRouter.navigate(RouterContract.PATH_SECOND)
        assertEquals(TRouterResult.NotInitialized, result)
        assertEquals("未初始化不应输出日志", 0, logs.count())
    }

    /** 重复 install 同一注册表：幂等不抛异常，路由仍可用，导航成功。 */
    @Test
    fun repeatedInstallIsIdempotent() {
        TRouter.install(provideRegistry()) // 第二次安装
        val result = TRouter.navigate(RouterContract.PATH_SECOND)
        assertTrue("重复 install 后导航应仍成功: $result", result is TRouterResult.Success)
        val loaderStarts = logs.lines.count { it.contains("[GroupLoader][load][start]") }
        val expectedRounds = 2 * provideRegistry().loaders().size // setUp 1 次 + 本用例重复 1 次
        assertEquals("两次 install 应各输出一轮（每 loader 一条）分组加载日志", expectedRounds, loaderStarts)
    }

    /** traceId 贯穿一致性：同一次 navigate 的 entry 与 exit 日志共享同一 traceId。 */
    @Test
    fun traceIdConsistentBetweenEntryAndExit() {
        val result = TRouter.navigate(RouterContract.PATH_SECOND)
        assertTrue("导航应成功: $result", result is TRouterResult.Success)

        val entry = logs.lines.first { it.contains("[navigate][entry]") }
        val exit = logs.lines.first { it.contains("[navigate][exit]") }
        val pattern = Regex("traceId=([0-9a-f]+)")
        val entryId = pattern.find(entry)?.groupValues?.get(1)
        val exitId = pattern.find(exit)?.groupValues?.get(1)
        assertTrue("entry 日志应带 traceId: $entry", entryId != null)
        assertTrue("exit 日志应带 traceId: $exit", exitId != null)
        assertEquals("entry/exit 应共享同一 traceId", entryId, exitId)
    }

    /** 未注册路径：返回 NotFound（携带原 path）并触发 config.onLost（UI 层见 testLostNavigation）。 */
    @Test
    fun lostPathReturnsNotFoundAndTriggersOnLost() {
        val result = TRouter.navigate(RouterContract.PATH_UNREGISTERED)
        assertEquals(TRouterResult.NotFound(RouterContract.PATH_UNREGISTERED), result)
        assertEquals(listOf(RouterContract.PATH_UNREGISTERED), lostPaths)
    }

    /** S07-V3（C5）聚合唯一性：DemoRouteRegistry 聚合 host+feature 后无重复 path、模块归属正确。 */
    @Test
    fun aggregatedRegistryHasNoDuplicatePaths() {
        val routes = TRouter.registeredRoutes()
        val paths = routes.map { it.path }
        assertEquals("聚合后不应有重复 path", paths.size, paths.toSet().size)
        assertEquals(
            "应为 6 条路由（host 2 + feature-demo 3 + feature-about 1）: $paths",
            6,
            paths.size,
        )
        assertTrue(
            "应覆盖全部契约路径: $paths",
            paths.containsAll(
                listOf(
                    RouterContract.PATH_MAIN,
                    RouterContract.PATH_SECOND,
                    RouterContract.PATH_FRAGMENT_DEMO,
                    RouterContract.PATH_MOCK_SECOND,
                    RouterContract.PATH_ABOUT,
                    RouterContract.PATH_REMOTE_SECOND,
                ),
            ),
        )
        val targets = routes.map { it.targetClassName }
        assertTrue("host 路由应指向 MainActivity: $targets", targets.contains("com.demo.trouter.MainActivity"))
        assertTrue("feature-demo 路由应指向 feature.demo 包: $targets", targets.any { it.contains("feature.demo.SecondActivity") })
        assertTrue("feature-about 路由应指向 feature.about 包: $targets", targets.any { it.contains("feature.about.AboutActivity") })
    }

    /** S07-V3（C5）跨模块重复防线：fake 重复注册 → duplicate 告警日志 + first-wins（保留先注册者）。 */
    @Test
    fun duplicateRegistrationIsLoggedAndFirstWins() {
        // fake 注册表声明一条与 /main 冲突的重复路由（指向不存在的假目标类，仅用于注册期验证）
        val fake = object : GroupLoaderRegistry {
            override fun loaders(): List<GroupLoader> = listOf(
                object : GroupLoader {
                    override val group: String = "fake"
                    override fun routeMetas(): List<RouteMeta> = listOf(
                        RouteMeta(
                            path = RouterContract.PATH_MAIN,
                            group = "fake",
                            targetClassName = "com.demo.trouter.fake.ClashActivity",
                            kind = RouteTargetKind.ACTIVITY,
                        ),
                    )
                },
            )
        }
        TRouter.install(fake)

        val join = logs.lines.joinToString("\n")
        assertTrue("应输出 duplicate 告警:\n$join", join.contains("[RouteTable][duplicate] path=${RouterContract.PATH_MAIN}"))
        assertTrue("应保留先注册者（host MainActivity）:\n$join", join.contains("keep=com.demo.trouter.MainActivity"))

        val kept = TRouter.registeredRoutes().first { it.path == RouterContract.PATH_MAIN }
        assertEquals("first-wins：/main 仍指向先注册的 MainActivity", "com.demo.trouter.MainActivity", kept.targetClassName)
    }
}
