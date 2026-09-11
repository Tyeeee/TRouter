package com.demo.trouter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.demo.trouter.generated.TRouterTargetInterceptorNames
import com.trouter.core.api.ChainOutcome
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.InterceptorChain
import com.trouter.core.api.RouteInterceptor
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult
import com.trouter.core.api.WrappingInterceptor
import com.trouter.core.testing.BaseTRouterTest
import com.trouter.core.testing.TestConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 目标级拦截器测试（只给某个页面挂拦截器，场景 S17；方案 docs/Backlog补全方案-动态化与硬化.md §3）。
 *
 * 用 RemoteSecondActivity（@Interceptor(names=["remoteAudit"])）作宿主验证：
 * 全局链之后追加目标链；未绑定标识 = 明确 Blocked（不静默）；重复绑定拒绝；非目标路由不受影响。
 */
@RunWith(AndroidJUnit4::class)
class TRouterTargetInterceptorTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    private fun configWithTargetResolver() = TestConfig(
        isDebug = true,
        logSink = logs,
        onLost = { lostPaths += it },
        targetInterceptorResolver = { className -> TRouterTargetInterceptorNames.namesOf(className) },
    )

    /** S17-1：绑定后，目标页导航触发目标级拦截器（全局之后）；非目标路由不受影响。 */
    @Test
    fun targetInterceptorRunsForAnnotatedTargetOnly() {
        val events = mutableListOf<String>()
        reInit(configWithTargetResolver())
        assertTrue(
            "remoteAudit 应先绑定",
            TRouter.bindTargetInterceptor(
                "remoteAudit",
                object : WrappingInterceptor {
                    override fun intercept(chain: InterceptorChain): ChainOutcome {
                        events.add("target-before")
                        val o = chain.proceed()
                        events.add("target-after")
                        return o
                    }
                },
            ),
        )

        // 目标路由：跨进程页面经本地 navigate 也会命中（RemoteSecondActivity 在本地注册表中存在）
        val result = TRouter.navigate(RouterContract.PATH_REMOTE_SECOND)
        assertTrue("目标页应成功: $result", result is TRouterResult.Success)
        assertEquals("应触发目标级包裹（before/open/after 顺序隐含于事件序）",
            listOf("target-before", "target-after"), events)

        // 非目标路由：不触发
        events.clear()
        val other = TRouter.navigate(RouterContract.PATH_ABOUT)
        assertTrue("非目标页应成功: $other", other is TRouterResult.Success)
        assertTrue("非目标路由不应触发目标级拦截器", events.isEmpty())
    }

    /** S17-2：未绑定标识 → navigate 返回明确 Blocked（不静默放行、不崩溃）。 */
    @Test
    fun missingTargetBindingBlocksWithClearReason() {
        reInit(configWithTargetResolver()) // 不绑定 remoteAudit
        val result = TRouter.navigate(RouterContract.PATH_REMOTE_SECOND)
        assertTrue("应 Blocked: $result", result is TRouterResult.Blocked)
        assertTrue("reason 应含未注册提示: ${(result as TRouterResult.Blocked).reason}",
            result.reason.contains("未注册") || result.reason.contains("bindTargetInterceptor"))
        assertTrue("onLost 不应触发", lostPaths.isEmpty())
    }

    /** S17-3：同名重复绑定拒绝；解绑后恢复"未绑定→Blocked"。 */
    @Test
    fun duplicateBindRejectedAndUnbindRestores() {
        reInit(configWithTargetResolver())
        val first = object : WrappingInterceptor {
            override fun intercept(chain: InterceptorChain): ChainOutcome = chain.proceed()
        }
        val second = object : WrappingInterceptor {
            override fun intercept(chain: InterceptorChain): ChainOutcome = chain.proceed()
        }
        assertTrue(TRouter.bindTargetInterceptor("remoteAudit", first))
        assertFalse("同名重复绑定应拒绝", TRouter.bindTargetInterceptor("remoteAudit", second))
        assertEquals(1, TRouter.registeredTargetInterceptors().size)

        assertTrue("解绑应成功", TRouter.unbindTargetInterceptor("remoteAudit"))
        assertTrue("解绑后再导航目标应因缺绑定 Blocked",
            TRouter.navigate(RouterContract.PATH_REMOTE_SECOND) is TRouterResult.Blocked)
    }

    /** S17-4：目标级拦截器在全局链之后执行（日志 index 顺序断言：全局先、目标后）。 */
    @Test
    fun targetRunsAfterGlobalChain() {
        reInit(configWithTargetResolver())
        val global = RouteInterceptor { _, _ -> com.trouter.core.api.InterceptorDecision.Continue }
        TRouter.addInterceptor(global)
        TRouter.bindTargetInterceptor(
            "remoteAudit",
            object : WrappingInterceptor {
                override fun intercept(chain: InterceptorChain): ChainOutcome = chain.proceed()
            },
        )

        val result = TRouter.navigate(RouterContract.PATH_REMOTE_SECOND)
        assertTrue(result is TRouterResult.Success)

        val evals = logs.lines.filter { it.contains("[interceptor][eval]") }
        assertEquals("全局 1 + 目标 1 共 2 条 eval", 2, evals.size)
        assertTrue("全局在前（index=1）", evals[0].contains("index=1") && !evals[0].contains("Wrap"))
        assertTrue("目标包裹在后（index=2 Wrap）", evals[1].contains("index=2") && evals[1].contains("Wrap"))
    }
}
