package com.demo.trouter.backtest

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.demo.trouter.DemoRouteRegistry
import com.demo.trouter.DemoStatsApi
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouteTargetKind
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterIntent
import com.trouter.core.api.TRouterResult
import com.trouter.core.testing.BaseTRouterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 回测的"信封"部分：**未初始化 / 重新初始化**这两个状态在回测台里跑不了
 * （回测台依赖 App 已经装配好，一旦把它重置，后面的节点就全废了）。
 *
 * 但它们恰恰是最容易出事的地方——忘了 init、装配顺序写反、重置后没恢复，
 * 这类问题的表现通常是"线上白屏"而不是"测试失败"。因此单独用一个类把这几条堵上。
 */
@RunWith(AndroidJUnit4::class)
class BacktestEnvelopeTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    /** 没初始化时，**每一个**对外入口都要给出确定答复，而不是崩溃或静默。 */
    @Test
    fun everyEntryPointAnswersDeterministicallyBeforeInit() {
        resetRouterState()

        assertEquals(
            "navigate 未初始化应答",
            TRouterResult.NotInitialized,
            TRouter.navigate(RouterContract.PATH_SECOND),
        )
        assertEquals(
            "navigateForResult 未初始化应答",
            TRouterResult.NotInitialized,
            TRouter.navigateForResult(RouterContract.PATH_SECOND, 1, null),
        )
        assertEquals(
            "navigateUri 未初始化应答",
            TRouterResult.NotInitialized,
            TRouter.navigateUri(Uri.parse("trouter://app/second")),
        )
        assertEquals(
            "buildIntent 未初始化应答",
            TRouterIntent.NotInitialized,
            TRouter.buildIntent(RouterContract.PATH_SECOND),
        )

        // navigateAsync 的契约是"回调恒在主线程"，因此这里必须等它，不能立刻读
        val asyncLatch = java.util.concurrent.CountDownLatch(1)
        var asyncDelivered: TRouterResult? = null
        TRouter.navigateAsync(RouterContract.PATH_SECOND) { r ->
            asyncDelivered = r
            asyncLatch.countDown()
        }
        assertTrue(
            "navigateAsync 未初始化时也应回调",
            asyncLatch.await(5, java.util.concurrent.TimeUnit.SECONDS),
        )
        assertEquals("navigateAsync 未初始化应答", TRouterResult.NotInitialized, asyncDelivered)

        var remoteDelivered: TRouterResult? = null
        TRouter.navigateRemote(RouterContract.PATH_SECOND) { remoteDelivered = it }
        assertEquals("navigateRemote 未初始化应答", TRouterResult.NotInitialized, remoteDelivered)

        var serviceReply: String? = null
        TRouter.callRemoteService("demoClock") { serviceReply = it }
        assertTrue("callRemoteService 未初始化应给出错误串", TRouter.isRemoteEndpointError(serviceReply ?: ""))

        assertTrue("registeredRoutes 未初始化应为空", TRouter.registeredRoutes().isEmpty())
        assertTrue("registeredInterceptors 未初始化应为空", TRouter.registeredInterceptors().isEmpty())
        assertTrue("registeredServices 未初始化应为空", TRouter.registeredServices().isEmpty())
        assertTrue("checkRouteTargets 未初始化应为空", TRouter.checkRouteTargets().isEmpty())
        assertFalse("registerRoute 未初始化应失败", TRouter.registerRoute(routeMeta(ENVELOPE_ROUTE_A)))
        assertFalse("registerService 未初始化应失败", TRouter.registerService(DemoStatsApi::class.java, com.demo.trouter.DemoStatsApiImpl()))
        assertFalse("addInterceptor 未初始化应失败", TRouter.addInterceptor(noopInterceptor()))
        assertFalse("importRouteMapJson 未初始化应失败", TRouter.importRouteMapJson("[]"))
        assertFalse("applyRouteConfig 未初始化应失败", TRouter.applyRouteConfig(emptyList(), emptyList()))
    }

    /** 装配顺序写反（先 install 后 init）必须当场报错，而不是留一个空路由表跑下去。 */
    @Test
    fun installBeforeInitFailsLoudly() {
        resetRouterState()
        val error = runCatching { TRouter.install(DemoRouteRegistry) }.exceptionOrNull()
        assertTrue("先 install 后 init 应当抛错，实际没有抛", error != null)
        assertTrue(
            "错误信息应说明顺序要求，实际=${error?.message}",
            error?.message?.contains("init") == true,
        )
    }

    /** 重置后重新装配：一切照常可用（回测台每一轮开头做的正是这件事）。 */
    @Test
    fun reinitAfterResetRestoresFullFunction() {
        resetRouterState()
        assertEquals(TRouterResult.NotInitialized, TRouter.navigate(RouterContract.PATH_SECOND))

        reInit(createConfig())
        val result = TRouter.navigate(RouterContract.PATH_SECOND)
        assertTrue("重新装配后应能正常导航，实际=$result", result is TRouterResult.Success)
        assertTrue("重新装配后路由表应非空", TRouter.registeredRoutes().isNotEmpty())
    }

    /** 未初始化的重建也要一致：重置是彻底的，不会留下上一轮的路由/拦截器。 */
    @Test
    fun resetLeavesNoResidue() {
        TRouter.registerRoute(routeMeta(ENVELOPE_ROUTE_B))
        assertTrue(TRouter.registeredRoutes().any { it.path == ENVELOPE_ROUTE_B })

        resetRouterState()

        assertTrue("重置后不该残留动态路由", TRouter.registeredRoutes().isEmpty())
        assertTrue("重置后不该残留拦截器", TRouter.registeredInterceptors().isEmpty())
    }

    private fun routeMeta(path: String) = RouteMeta(
        path = path,
        group = RouterContract.GROUP_DYNAMIC,
        targetClassName = "com.demo.trouter.MainActivity",
        kind = RouteTargetKind.ACTIVITY,
    )

    private fun noopInterceptor() = com.trouter.core.api.RouteInterceptor { _, _ ->
        com.trouter.core.api.InterceptorDecision.Continue
    }

    private companion object {
        /** 信封用例专用的临时路径（同样只写一次，避免调用点硬编码）。 */
        const val ENVELOPE_ROUTE_A: String = "/bt-never"
        const val ENVELOPE_ROUTE_B: String = "/bt-residue"
    }
}
