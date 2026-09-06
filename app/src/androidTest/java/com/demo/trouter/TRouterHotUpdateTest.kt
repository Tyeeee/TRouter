package com.demo.trouter

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.DynamicRouteCodec
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
import java.io.File

/**
 * 动态路由热更/持久化测试（F，场景 S18；方案 docs/Backlog补全方案-动态化与硬化.md §4）。
 * 覆盖：apply 原子性（任一冲突整批失败、零落地）、先移除后注册的热更语义、
 * 导出→持久化→恢复 一致、编解码容错。
 */
@RunWith(AndroidJUnit4::class)
class TRouterHotUpdateTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    private fun meta(path: String) = RouteMeta(
        path = path,
        group = RouterContract.GROUP_DYNAMIC,
        targetClassName = DynamicDemoActivity::class.java.name,
        kind = RouteTargetKind.ACTIVITY,
    )

    /** F-1：apply 遇任一冲突 → 整批失败，任何新路由都不落地（原子性）。 */
    @Test
    fun applyIsAtomicOnConflict() {
        val existing = RouterContract.PATH_SECOND // 静态路由
        val a = meta("/hot/a")
        val b = meta(existing)   // 与“移除后仍存在”的静态路径冲突
        val c = meta("/hot/c")

        assertFalse("整批应因 /second 冲突而失败", TRouter.applyRouteConfig(removes = emptyList(), adds = listOf(a, b, c)))

        assertEquals("冲突批必须零落地（原子性）", emptyList<String>(),
            TRouter.registeredRoutes().map { it.path }.filter { it.startsWith("/hot/") })
        assertTrue("静态 /second 不受影响", TRouter.navigate(existing) is TRouterResult.Success)
    }

    /** F-2：apply = 先移除后注册（热更语义：旧目标失效、新目标生效）。 */
    @Test
    fun applyRemovesThenAdds() {
        val oldPath = "/hot/old"
        val newPath = "/hot/new"
        assertTrue(TRouter.registerRoute(meta(oldPath)))

        assertTrue(TRouter.applyRouteConfig(removes = listOf(oldPath), adds = listOf(meta(newPath))))

        assertEquals(TRouterResult.NotFound(oldPath), TRouter.navigate(oldPath))
        val opened = TRouter.navigate(newPath)
        assertTrue("新目标应可导航: $opened", opened is TRouterResult.Success)
    }

    /** F-3：保存 → 注销全部 → 恢复 → 一致可导航；文件为私有临时文件（测试内清理）。 */
    @Test
    fun saveLoadRoundTripRestoresDynamicRoutes() {
        val file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "trouter-dyn-test.txt")
        file.delete()

        val d1 = "/hot/save1"
        val d2 = "/hot/save2"
        assertTrue(TRouter.registerRoute(meta(d1)))
        assertTrue(TRouter.registerRoute(meta(d2)))
        assertTrue(TRouter.saveDynamicRoutes(file))

        assertTrue(TRouter.unregisterRoute(d1))
        assertTrue(TRouter.unregisterRoute(d2))
        assertEquals(TRouterResult.NotFound(d1), TRouter.navigate(d1))

        assertTrue(TRouter.loadDynamicRoutes(file))
        assertTrue("恢复后 d1 可导航", TRouter.navigate(d1) is TRouterResult.Success)
        assertTrue("恢复后 d2 可导航", TRouter.navigate(d2) is TRouterResult.Success)
        assertEquals(
            "导出/恢复应一致",
            setOf(d1, d2),
            TRouter.exportDynamicRoutes().map { it.path }.toSet(),
        )
        file.delete()
    }

    /** F-4：编解码往返一致 + 坏行容错。 */
    @Test
    fun codecRoundTripAndTolerance() {
        val metas = listOf(meta("/codec/1"), meta("/codec/2"))
        val encoded = DynamicRouteCodec.encode(metas)
        assertEquals(metas.map { it.path }, DynamicRouteCodec.decode(encoded).map { it.path })

        val garbage = "this is a broken line\n${encoded}\ngarbage2"
        val decoded = DynamicRouteCodec.decode(garbage)
        assertEquals("坏行应被跳过，仅保留合法行", metas.map { it.path }, decoded.map { it.path })
    }
}
