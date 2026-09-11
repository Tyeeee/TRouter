package com.demo.trouter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.ChainOutcome
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.InterceptorChain
import com.trouter.core.api.InterceptorDecision
import com.trouter.core.api.RouteInterceptor
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterConfig
import com.trouter.core.api.TRouterResult
import com.trouter.core.api.WrappingInterceptor
import com.trouter.core.testing.BaseTRouterTest
import com.trouter.core.testing.TestConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 洋葱/双向链测试（前后包裹式拦截器，场景 S15；方案 docs/Backlog补全方案-动态化与硬化.md §1）。
 *
 * 覆盖：
 * 1. 包裹拦截器可做「前置 → proceed() 放行并打开 → 后置」的洋葱观察；
 * 2. 链中任一处短路（Block）时，其后的包裹/原子拦截器不再执行；
 * 3. 包裹拦截器不调用 proceed() 直接拦截 → Blocked，不打开目标；
 * 4. proceed() 二次调用被守卫拦截（抛错→按拦截器故障 Blocked），防止重复打开；
 * 5. 包裹拦截器返回 Redirected → TRouter 以同 traceId 重入新跳。
 *
 * 本批按协议只测 前后包裹式拦截器（含紧邻的旧拦截器契约/UI 用例做执行器回归）。
 */
@RunWith(AndroidJUnit4::class)
class TRouterOnionChainTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    private fun reInit(vararg members: com.trouter.core.api.RouteChainMember) {
        reInit(
            TestConfig(
                isDebug = true,
                logSink = logs,
                onLost = { lostPaths += it },
                interceptors = members.toList(),
            ),
        )
    }

    /** 前后包裹式拦截器-1（洋葱顺序）：wrapper 前置 → proceed → 目标打开 → wrapper 后置。 */
    @Test
    fun wrapperRunsBeforeAndAfterOpen() {
        val events = mutableListOf<String>()
        val audit = object : WrappingInterceptor {
            override fun intercept(chain: InterceptorChain): ChainOutcome {
                events.add("before:${chain.meta.path}")
                val outcome = chain.proceed()
                events.add("after:${chain.meta.path}:${outcome.javaClass.simpleName}")
                return outcome
            }
        }
        reInit(audit)

        val result = TRouter.navigate(RouterContract.PATH_SECOND)

        assertTrue("应 Success: $result", result is TRouterResult.Success)
        assertEquals(
            "洋葱应严格 前置→打开→后置",
            listOf("before:/second", "after:/second:Opened"),
            events,
        )
        assertTrue("onLost 不应触发", lostPaths.isEmpty())
    }

    /** 前后包裹式拦截器-2（短路）：前方原子拦截器 Block 后，包裹拦截器与其余成员不再执行。 */
    @Test
    fun blockSkipsLaterWrappers() {
        var wrapperCalled = false
        val gate = RouteInterceptor { _, _ -> InterceptorDecision.Block("先拦截") }
        val audit = object : WrappingInterceptor {
            override fun intercept(chain: InterceptorChain): ChainOutcome {
                wrapperCalled = true
                return chain.proceed()
            }
        }
        reInit(gate, audit)

        val result = TRouter.navigate(RouterContract.PATH_SECOND)

        assertTrue("应 Blocked: $result", result is TRouterResult.Blocked)
        assertEquals("短路后包裹拦截器不应执行", false, wrapperCalled)

        val evals = logs.lines.filter { it.contains("[interceptor][eval]") }
        assertEquals("只应有一条 eval（Block 短路）", 1, evals.size)
    }

    /** 前后包裹式拦截器-3（不放行即拦截）：wrapper 不调用 proceed() 直接 Block → 不打开目标、结果 Blocked。 */
    @Test
    fun wrapperCanBlockWithoutProceed() {
        val blocker = object : WrappingInterceptor {
            override fun intercept(chain: InterceptorChain): ChainOutcome =
                ChainOutcome.Blocked(RouterContract.PATH_SECOND, "wrapper 直接拦截")
        }
        reInit(blocker)

        val result = TRouter.navigate(RouterContract.PATH_SECOND)

        assertEquals(TRouterResult.Blocked(RouterContract.PATH_SECOND, "wrapper 直接拦截"), result)
        assertTrue("onLost 不应触发", lostPaths.isEmpty())
        assertTrue("日志 end 应为 Block",
            logs.lines.joinToString("\n").contains("decision=Block(reason=wrapper 直接拦截)"))
    }

    /** 前后包裹式拦截器-4（二次放行守卫）：proceed() 调两次 → 守卫抛错，按拦截器故障 Blocked（不崩溃、不重复开页语义）。 */
    @Test
    fun doubleProceedIsGuarded() {
        val bad = object : WrappingInterceptor {
            override fun intercept(chain: InterceptorChain): ChainOutcome {
                val first = chain.proceed()
                chain.proceed() // 违规：第二次放行 → 应被守卫拦截
                return first
            }
        }
        reInit(bad)

        val result = TRouter.navigate(RouterContract.PATH_SECOND)

        assertTrue("二次放行应转为 Blocked(interceptor error): $result", result is TRouterResult.Blocked)
        assertTrue("reason 应含守卫错误: ${(result as TRouterResult.Blocked).reason}",
            result.reason.contains("proceed 只能调用一次") || result.reason.contains("interceptor error"))
        assertTrue("onLost 不应触发", lostPaths.isEmpty())
    }

    /** 前后包裹式拦截器-5（wrapper Redirect）：仅对源 path 重定向、目标跳放行 → 同 traceId 重入并打开 Mock 目标。 */
    @Test
    fun wrapperRedirectReentersWithSameTrace() {
        val redirector = object : WrappingInterceptor {
            override fun intercept(chain: InterceptorChain): ChainOutcome =
                if (chain.meta.path == RouterContract.PATH_SECOND) {
                    ChainOutcome.Redirected(RouterContract.PATH_MOCK_SECOND)
                } else {
                    chain.proceed()
                }
        }
        reInit(redirector)

        val result = TRouter.navigate(RouterContract.PATH_SECOND)

        assertTrue("重定向后应打开 Mock 目标: $result", result is TRouterResult.Success)
        assertEquals(RouterContract.PATH_MOCK_SECOND, (result as TRouterResult.Success).meta.path)

        val join = logs.lines.joinToString("\n")
        assertTrue("原跳出口应 Redirect:\n$join", join.contains("result=Redirect(target=${RouterContract.PATH_MOCK_SECOND})"))
        assertTrue("新跳应为 Mock 目标 Success:\n$join", join.contains("result=Success(meta=${RouterContract.PATH_MOCK_SECOND}"))
        val ids = Regex("traceId=([0-9a-f]+)").findAll(join).map { it.groupValues[1] }.toList().distinct()
        assertEquals("同一次导航 traceId 应贯穿: $ids", 1, ids.size)
    }

    /** 前后包裹式拦截器-6（旧原子与包裹混排）：顺序 = 旧原子 Continue → wrapper 包裹(放行) → 打开。 */
    @Test
    fun mixedOldAndWrapperOrder() {
        val events = mutableListOf<String>()
        val pass = RouteInterceptor { _, _ -> events.add("old"); InterceptorDecision.Continue }
        val wrapper = object : WrappingInterceptor {
            override fun intercept(chain: InterceptorChain): ChainOutcome {
                events.add("wrap-before")
                val o = chain.proceed()
                events.add("wrap-after")
                return o
            }
        }
        reInit(pass, wrapper)

        val result = TRouter.navigate(RouterContract.PATH_ABOUT)

        assertTrue("应 Success: $result", result is TRouterResult.Success)
        assertEquals(
            "混排执行序应为 旧原子→包裹前置→打开→包裹后置",
            listOf("old", "wrap-before", "wrap-after"),
            events,
        )
    }
}
