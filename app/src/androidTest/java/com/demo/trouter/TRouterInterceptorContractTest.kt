package com.demo.trouter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.InterceptorDecision
import com.trouter.core.api.MockInterceptor
import com.trouter.core.api.RouteInterceptor
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterConfig
import com.trouter.core.api.TRouterResult
import com.trouter.core.testing.BaseTRouterTest
import com.trouter.core.testing.TestConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TRouter 拦截器契约测试（场景 S10，见 docs/V2.0-拦截器方案.md §7）。
 *
 * 说明：拦截器的「行为驱动」UI 场景（S08 门禁 / S09 Mock）由 TRouterInterceptorUiTest 覆盖；
 * 本类只针对**无法/不宜用 UI 驱动**的契约分支直接调 API 验证：
 * 顺序与短路（C2）、Blocked 结果与 onLost 隔离（C3）、Redirect 语义与跳数上限（C4）、
 * isDebug=false 时拦截仍生效（R3 澄清）。
 * 每个用例经 reInit 注入**用例私有**拦截器实例，避免跨用例状态残留（T2）。
 */
@RunWith(AndroidJUnit4::class)
class TRouterInterceptorContractTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    /** 以给定拦截器重建配置（isDebug=true + 日志收集 + onLost 记录）。 */
    private fun reInitWith(vararg interceptors: RouteInterceptor) {
        reInit(
            TestConfig(
                isDebug = true,
                logSink = logs,
                onLost = { lostPaths += it },
                interceptors = interceptors.toList(),
            ),
        )
    }

    /** S10-1（C2/C3）：拦截器按列表顺序求值；首个 Block 短路，后置不再执行；不触发 onLost。 */
    @Test
    fun blockShortCircuitsAndPreservesOrder() {
        var tailCalled = false
        val blocker = GateInterceptor().apply { enabled = true }
        val tail = RouteInterceptor { _, _ ->
            tailCalled = true
            InterceptorDecision.Continue
        }
        reInitWith(blocker, tail) // blocker 在前 → tail 不应执行

        val result = TRouter.navigate(RouterContract.PATH_SECOND)

        assertEquals("应返回 Blocked: $result", TRouterResult.Blocked(RouterContract.PATH_SECOND, "演示门禁开启：拦截 ${RouterContract.PATH_SECOND}"), result)
        assertEquals("Block 短路后置拦截器不应执行", false, tailCalled)
        assertTrue("Blocked 不应触发 onLost", lostPaths.isEmpty())

        val join = logs.lines.joinToString("\n")
        assertTrue("出口日志应为 Blocked:\n$join", join.contains("result=Blocked(path=${RouterContract.PATH_SECOND}"))
        val evals = logs.lines.filter { it.contains("[interceptor][eval]") }
        assertEquals("仅 1 条 eval（Block 短路）", 1, evals.size)
        assertTrue("应记录 index=1 Block:\n${evals[0]}", evals[0].contains("index=1") && evals[0].contains("decision=Block("))
    }

    /** S10-2（C4）：Redirect 到已注册 Mock 路径 → 打开目标、traceId 贯穿两跳。 */
    @Test
    fun redirectToRegisteredPathOpensMockAndSharesTraceId() {
        val mock = MockInterceptor(
            mapOf(RouterContract.PATH_SECOND to RouterContract.PATH_MOCK_SECOND),
        ).apply { enabled = true }
        reInitWith(mock)

        val result = TRouter.navigate(RouterContract.PATH_SECOND)

        assertTrue("应成功打开 Mock 目标: $result", result is TRouterResult.Success)
        assertEquals(RouterContract.PATH_MOCK_SECOND, (result as TRouterResult.Success).meta.path)

        val join = logs.lines.joinToString("\n")
        assertTrue("原跳出口应记 Redirect:\n$join", join.contains("result=Redirect(target=${RouterContract.PATH_MOCK_SECOND})"))
        assertTrue("最终出口应为 Mock 目标 Success:\n$join", join.contains("result=Success(meta=${RouterContract.PATH_MOCK_SECOND}"))
        val ids = Regex("traceId=([0-9a-f]+)").findAll(join).map { it.groupValues[1] }.toList().distinct()
        assertEquals("同一次导航 traceId 应贯穿所有跳数: $ids", 1, ids.size)
        assertTrue("onLost 不应触发", lostPaths.isEmpty())
    }

    /** S10-3（C4）：Redirect 到未注册路径 → 按 NotFound 处理并触发 onLost（丢失语义）。 */
    @Test
    fun redirectToUnregisteredPathIsNotFoundAndTriggersOnLost() {
        val mock = MockInterceptor(
            mapOf(RouterContract.PATH_SECOND to RouterContract.PATH_UNREGISTERED),
        ).apply { enabled = true }
        reInitWith(mock)

        val result = TRouter.navigate(RouterContract.PATH_SECOND)

        assertEquals(TRouterResult.NotFound(RouterContract.PATH_UNREGISTERED), result)
        assertEquals(listOf(RouterContract.PATH_UNREGISTERED), lostPaths)
    }

    /** S10-4（C4）：Redirect 死循环被跳数上限截断 → Blocked(redirect loop)，不崩溃。 */
    @Test
    fun redirectLoopIsCappedAndReturnsBlocked() {
        val loop = RouteInterceptor { _, _ ->
            InterceptorDecision.Redirect(RouterContract.PATH_SECOND) // 自指：恒重定向
        }
        reInitWith(loop)

        val result = TRouter.navigate(RouterContract.PATH_SECOND)

        assertTrue("应为 Blocked: $result", result is TRouterResult.Blocked)
        assertTrue("reason 应含 redirect loop: ${(result as TRouterResult.Blocked).reason}", result.reason.contains("redirect loop"))
        val entryCount = logs.lines.count { it.contains("[navigate][entry]") }
        assertEquals("应恰有 0..3 共 4 跳 entry（第 4 次重定向触发上限）", 4, entryCount)
        assertTrue("loop 截断不应触发 onLost", lostPaths.isEmpty())
    }

    /** S10-5（C3/R3 澄清）：isDebug=false 只关日志；拦截行为仍生效。 */
    @Test
    fun interceptionAppliesWhenDebugDisabled() {
        val blocker = GateInterceptor().apply { enabled = true }
        reInit(
            TestConfig(
                isDebug = false,
                logSink = logs,
                onLost = { lostPaths += it },
                interceptors = listOf(blocker),
            ),
        )

        val result = TRouter.navigate(RouterContract.PATH_SECOND)

        assertTrue("isDebug=false 拦截仍应生效: $result", result is TRouterResult.Blocked)
        assertEquals("isDebug=false 日志应静默", 0, logs.count())
        assertTrue("onLost 不应触发", lostPaths.isEmpty())
    }

    /** S10-6（Fail-Fast）：拦截器自身抛异常 = 视为 Block，不崩溃、不打开目标、不触发 onLost。 */
    @Test
    fun interceptorCrashIsTreatedAsBlockedNotCrash() {
        val crash = RouteInterceptor { _, _ -> throw IllegalStateException("boom") }
        reInitWith(crash)

        val result = TRouter.navigate(RouterContract.PATH_ABOUT)

        assertTrue("拦截器故障应返回 Blocked: $result", result is TRouterResult.Blocked)
        assertTrue("reason 应含 interceptor error: ${(result as TRouterResult.Blocked).reason}", result.reason.contains("interceptor error"))
        assertTrue("onLost 不应触发", lostPaths.isEmpty())
    }
}
