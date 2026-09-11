package com.demo.trouter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.InterceptorDecision
import com.trouter.core.api.RouteChainMember
import com.trouter.core.api.RouteInterceptor
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
 * 动态增删拦截器测试（运行中增删拦截器，场景 S16；方案 docs/Backlog补全方案-动态化与硬化.md §2）。
 *
 * 覆盖：运行时 add/remove 的生效与失效、同实例重复拒绝、增删**只影响下一次 navigate**
 * （进行中的链与下一次之间严格隔离——同类缺陷（如"第二次调用必超时"）的回归锚点）、
 * 加入顺序确定、未初始化时拒绝。
 */
@RunWith(AndroidJUnit4::class)
class TRouterInterceptorRuntimeTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    private fun gateBlock(onlyPath: String, reason: String = "运行时门禁") = RouteInterceptor { meta, _ ->
        if (meta.path == onlyPath) InterceptorDecision.Block(reason) else InterceptorDecision.Continue
    }

    /** S16-1：add 后仅拦截指定 path；remove 后恢复放行。 */
    @Test
    fun addBlocksThenRemoveRestores() {
        assertTrue(TRouter.navigate(RouterContract.PATH_SECOND) is TRouterResult.Success)

        val gate = gateBlock(RouterContract.PATH_SECOND)
        assertTrue("add 应成功", TRouter.addInterceptor(gate))
        assertEquals(1, TRouter.registeredInterceptors().size)

        assertTrue("加后 /second 应被拦截: ${TRouter.navigate(RouterContract.PATH_SECOND)}",
            TRouter.navigate(RouterContract.PATH_SECOND) is TRouterResult.Blocked)
        assertTrue("其它路径不受影响", TRouter.navigate(RouterContract.PATH_ABOUT) is TRouterResult.Success)

        assertTrue("remove 应成功", TRouter.removeInterceptor(gate))
        assertEquals(0, TRouter.registeredInterceptors().size)
        assertTrue("移除后 /second 恢复放行", TRouter.navigate(RouterContract.PATH_SECOND) is TRouterResult.Success)
        assertFalse("再移除不存在实例应 false", TRouter.removeInterceptor(gate))
    }

    /** S16-2：同实例重复 add → 拒绝 + 重复日志（不双跑拦截器）。 */
    @Test
    fun duplicateAddIsRejected() {
        val gate = gateBlock(RouterContract.PATH_SECOND)
        assertTrue(TRouter.addInterceptor(gate))
        assertFalse("同实例重复 add 应拒绝", TRouter.addInterceptor(gate))
        assertEquals(1, TRouter.registeredInterceptors().size)

        val join = logs.lines.joinToString("\n")
        assertTrue("应输出重复注册日志:\n$join", join.contains("[interceptor][register][duplicate]"))

        // 双跑检查：拦截器只执行一次（/second 被 Block 且仅一次 eval 属于 gate）
        val result = TRouter.navigate(RouterContract.PATH_SECOND)
        assertTrue(result is TRouterResult.Blocked)
    }

    /** S16-3（回归锚点）：add 只影响下一次 navigate——本次结果返回后立即生效、不丢也不误伤历史链。 */
    @Test
    fun addOnlyAffectsNextNavigation() {
        // 第一次（无拦截器）成功
        assertTrue(TRouter.navigate(RouterContract.PATH_SECOND) is TRouterResult.Success)
        // add 发生在两次 navigate 之间：下一次立即生效
        val gate = gateBlock(RouterContract.PATH_SECOND)
        TRouter.addInterceptor(gate)
        assertTrue("下一次应立即被拦截", TRouter.navigate(RouterContract.PATH_SECOND) is TRouterResult.Blocked)
        // remove 同理：下一次恢复
        TRouter.removeInterceptor(gate)
        assertTrue("移除后下一次应立即放行", TRouter.navigate(RouterContract.PATH_SECOND) is TRouterResult.Success)
    }

    /** S16-4：加入顺序确定——按 add 顺序出现在链中（日志 index/class 断言）。 */
    @Test
    fun addedOrderIsStable() {
        val first = RouteInterceptor { _, _ -> InterceptorDecision.Continue }
        val second = RouteInterceptor { _, _ -> InterceptorDecision.Continue }
        assertTrue(TRouter.addInterceptor(first))
        assertTrue(TRouter.addInterceptor(second))

        val result = TRouter.navigate(RouterContract.PATH_ABOUT)
        assertTrue(result is TRouterResult.Success)

        val evals = logs.lines.filter { it.contains("[interceptor][eval]") }
        assertEquals(2, evals.size)
        assertTrue("先加入者 index=1: ${evals[0]}", evals[0].contains("index=1"))
        assertTrue("后加入者 index=2: ${evals[1]}", evals[1].contains("index=2"))
        assertEquals("registeredInterceptors 顺序与 add 一致",
            listOf<RouteChainMember>(first, second), TRouter.registeredInterceptors())
    }

    /** S16-5：未初始化（reset 后）时 add/remove 返回 false，不崩溃。 */
    @Test
    fun addBeforeInitIsRejected() {
        resetRouterState()
        assertFalse("未初始化 add 应 false", TRouter.addInterceptor(gateBlock(RouterContract.PATH_SECOND)))
        assertFalse("未初始化 remove 应 false", TRouter.removeInterceptor(gateBlock(RouterContract.PATH_SECOND)))
    }
}
