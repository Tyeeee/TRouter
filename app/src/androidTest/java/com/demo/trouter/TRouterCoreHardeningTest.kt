package com.demo.trouter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.InterceptorDecision
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouteInterceptor
import com.trouter.core.api.RouteTargetKind
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.testing.BaseTRouterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 内核硬化测试（批 1：G8 Class 缓存落地 / G7 目标合法性校验 / G11 全局拦截优先级）。
 * 方案 docs/我们与开源Router差距分析.md 表 B。
 */
@RunWith(AndroidJUnit4::class)
class TRouterCoreHardeningTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    /** G7-1：全部已注册路由（静态+跨模块）目标类应可加载。 */
    @Test
    fun allRegisteredRoutesHaveLoadableTargets() {
        val missing = TRouter.checkRouteTargets()
        assertEquals("静态路由目标类应全部可加载（KSP 已保证），空清单: $missing", emptyList<RouteMeta>(), missing)
        assertTrue("校验不应输出 missing 日志",
            logs.lines.none { it.contains("[route][verify][missing]") })
    }

    /** G7-2：动态注册传错类名 → checkRouteTargets 能精确检出并告警（迟发现问题前置）。 */
    @Test
    fun dynamicRouteWithMissingTargetDetectedByVerify() {
        val fakePath = "/dynamic/fake-target"
        val ok = TRouter.registerRoute(
            RouteMeta(
                path = fakePath,
                group = RouterContract.GROUP_DYNAMIC,
                targetClassName = "com.demo.trouter.DoesNotExistActivity",
                kind = RouteTargetKind.ACTIVITY,
            ),
        )
        assertTrue(ok)
        val missing = TRouter.checkRouteTargets()
        assertEquals("应检出 1 条目标类缺失", listOf(fakePath), missing.map { it.path })
        assertTrue("应输出 missing 日志",
            logs.lines.joinToString("\n").contains("[route][verify][missing] path=$fakePath"))

        TRouter.unregisterRoute(fakePath)
        assertEquals("清理后无残留", emptyList<RouteMeta>(), TRouter.checkRouteTargets())
    }

    private class HighPriorityInterceptor : RouteInterceptor {
        override val priority: Int = 10
        override fun intercept(meta: com.trouter.core.api.RouteMeta, bundle: android.os.Bundle?): InterceptorDecision =
            InterceptorDecision.Continue
    }

    private class LowPriorityInterceptor : RouteInterceptor {
        override val priority: Int = 0
        override fun intercept(meta: com.trouter.core.api.RouteMeta, bundle: android.os.Bundle?): InterceptorDecision =
            InterceptorDecision.Continue
    }

    /** G11：全局链按 priority 降序执行（默认 0 者保持声明顺序——由既有 S16-4 继续守护）。 */
    @Test
    fun globalChainSortsByPriorityStably() {
        assertTrue(TRouter.addInterceptor(LowPriorityInterceptor()))
        assertTrue(TRouter.addInterceptor(HighPriorityInterceptor())) // 后加，但 priority 更高 → 应排前

        val result = TRouter.navigate(RouterContract.PATH_ABOUT)
        assertTrue("导航应成功: $result", result is com.trouter.core.api.TRouterResult.Success)

        val evals = logs.lines.filter { it.contains("[interceptor][eval]") }
        assertEquals(2, evals.size)
        assertTrue("高优先级应先执行（index=1 High）: ${evals[0]}",
            evals[0].contains("index=1") && evals[0].contains("HighPriorityInterceptor"))
        assertTrue("低优先级随后（index=2 Low）: ${evals[1]}",
            evals[1].contains("index=2") && evals[1].contains("LowPriorityInterceptor"))
    }
}
