package com.demo.trouter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.RouteMapCodec
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouteTargetKind
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult
import com.trouter.core.testing.BaseTRouterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private interface DemoClockService {
    fun now(): String
}

private class DemoClockImpl : DemoClockService {
    override fun now(): String = "clock"
}

/**
 * 批 3a：G6 路由表 JSON 导出/覆盖 + G2 进程内服务层。
 */
@RunWith(AndroidJUnit4::class)
class TRouterRouteMapAndServiceTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    private fun meta(path: String) = RouteMeta(
        path = path,
        group = RouterContract.GROUP_DYNAMIC,
        targetClassName = DynamicDemoActivity::class.java.name,
        kind = RouteTargetKind.ACTIVITY,
    )

    /** G6-1：动态集导出 JSON → 清空 → 导入覆盖 → 恢复可导航且静态不受影响；格式非法零变更。 */
    @Test
    fun jsonExportImportOverlaysDynamicOnly() {
        val d1 = "/map/save1"
        val d2 = "/map/save2"
        assertTrue(TRouter.registerRoute(meta(d1)))
        assertTrue(TRouter.registerRoute(meta(d2)))

        val json = RouteMapCodec.toJson(TRouter.exportDynamicRoutes())
        assertTrue("json 应含两条动态路由", json.contains(d1) && json.contains(d2))

        assertTrue(TRouter.unregisterRoute(d1))
        assertTrue(TRouter.unregisterRoute(d2))
        assertEquals(TRouterResult.NotFound(d1), TRouter.navigate(d1))

        assertTrue("导入覆盖应成功", TRouter.importRouteMapJson(json))
        assertTrue("恢复后 d1 可导航", TRouter.navigate(d1) is TRouterResult.Success)
        assertTrue("恢复后 d2 可导航", TRouter.navigate(d2) is TRouterResult.Success)
        assertTrue("静态 /second 不受影响", TRouter.navigate(RouterContract.PATH_SECOND) is TRouterResult.Success)
    }

    /** G6-2：非法 JSON 或与静态冲突 → 整批失败零变更。 */
    @Test
    fun invalidOrConflictingImportIsAtomicNoop() {
        val d = "/map/keep"
        assertTrue(TRouter.registerRoute(meta(d)))

        assertFalse("非法 JSON 应失败", TRouter.importRouteMapJson("{oops"))
        assertTrue("非法导入后原动态路由仍在", TRouter.exportDynamicRoutes().any { it.path == d })

        val conflictJson = RouteMapCodec.toJson(
            listOf(meta(d), meta(RouterContract.PATH_SECOND)), // 静态 /second 冲突
        )
        assertFalse("与静态冲突应整批失败", TRouter.importRouteMapJson(conflictJson))
        assertTrue("冲突批零落地（原 d 仍在，second 仍是静态语义）",
            TRouter.exportDynamicRoutes().any { it.path == d })
        assertTrue("静态 /second 导航正常", TRouter.navigate(RouterContract.PATH_SECOND) is TRouterResult.Success)
    }

    /** G6-3：编解码器纯函数往返 + 坏行 null。 */
    @Test
    fun codecRoundTripAndTolerance() {
        val metas = listOf(meta("/c/1"), meta("/c/2"))
        val encoded = RouteMapCodec.toJson(metas)
        val decoded = RouteMapCodec.fromJson(encoded)
        assertEquals(metas.map { it.path }, decoded!!.map { it.path })
        assertNull(RouteMapCodec.fromJson("not json"))
        assertEquals(emptyList<RouteMeta>(), RouteMapCodec.fromJson("[]"))
    }

    /** G2-1：注册/查找/重复拒绝/注销/未注册为 null；注册与注销有日志。 */
    @Test
    fun serviceRegisterFindUnregister() {
        assertTrue(TRouter.registerService(DemoClockService::class.java, DemoClockImpl()))
        val found = TRouter.findService(DemoClockService::class.java)
        assertEquals("clock", found?.now())
        assertTrue(TRouter.registeredServices().contains(DemoClockService::class.java))

        assertFalse("同接口重复注册应拒绝", TRouter.registerService(DemoClockService::class.java, DemoClockImpl()))

        assertTrue("注销应成功", TRouter.unregisterService(DemoClockService::class.java))
        assertNull("注销后 find 应 null", TRouter.findService(DemoClockService::class.java))

        val join = logs.lines.joinToString("\n")
        assertTrue("应有注册/注销日志:\n$join",
            join.contains("[service][register] interface=") && join.contains("[service][unregister] interface="))
    }
}
