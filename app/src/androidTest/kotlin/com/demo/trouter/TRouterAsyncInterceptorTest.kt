package com.demo.trouter

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.AsyncChain
import com.trouter.core.api.AsyncInterceptor
import com.trouter.core.api.ChainOutcome
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.InterceptorChain
import com.trouter.core.api.InterceptorDecision
import com.trouter.core.api.RouteChainMember
import com.trouter.core.api.RouteInterceptor
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult
import com.trouter.core.api.WrappingInterceptor
import com.trouter.core.testing.BaseTRouterTest
import com.trouter.core.testing.TestConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 异步拦截器测试（异步拦截器改造，场景 S23–S25）。
 *
 * 覆盖（每条都是对外承诺的行为，不是实现细节）：
 * 1. 异步放行 → 目标打开、结果回调**在主线程**且**只回调一次**；
 * 2. 异步 Block → Blocked（不打开、不触发 onLost）；
 * 3. 异步 Redirect → 打开改写后的目标；
 * 4. 超时 → Blocked（reason 含超时），且**迟到的放行被静默忽略**（不打开、不再回调）；
 * 5. 取消 → Blocked（已取消），迟到的放行同样被忽略；
 * 6. 同一异步成员重复终止（proceed 之后再 block）→ 抛 IllegalStateException（配置错误显性化）；
 * 7. 同步 navigate：**立即放行**的异步成员照常兼容（Success），**延迟放行**才明确 Blocked（提示改用 navigateAsync），绝不阻塞等待；
 * 8. 异步成员与同步成员**顺序**保持一致；
 * 9. 链中可连续出现多个异步成员（各自独立超时窗口）；
 * 10. 洋葱包裹拦截器不能跨异步成员 → 明确 Blocked（而不是静默错乱）。
 *
 * 说明：用例线程是 instrumentation 线程，异步结果由主线程回调，因此统一用 CountDownLatch 等待。
 */
@RunWith(AndroidJUnit4::class)
class TRouterAsyncInterceptorTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    // ------------------------------------------------------------------ 用例

    /** B-1：异步放行 → Success，回调在主线程且只回调一次。 */
    @Test
    fun asyncProceedOpensTargetWithSingleMainThreadCallback() {
        reInitWith(
            ControlledAsync("延时放行") { chain -> postDelayed(80) { chain.proceed() } },
        )
        val sink = openAsyncAndAwait(RouterContract.PATH_SECOND)

        val result = sink.assertDelivered()
        assertTrue("应为 Success，实际=$result", result is TRouterResult.Success)
        assertEquals(RouterContract.PATH_SECOND, (result as TRouterResult.Success).meta.path)
        assertTrue("回调必须在主线程", sink.onMainThread)
        assertEquals("结果只能回调一次", 1, sink.count.get())
        assertTrue("应有异步等待日志", logs.lines.any { it.contains("[interceptor][async][wait]") })
    }

    /** B-2：异步 Block → Blocked，不触发 onLost。 */
    @Test
    fun asyncBlockReturnsBlockedWithoutOnLost() {
        reInitWith(ControlledAsync("异步拦截") { chain -> chain.block("异步风控未通过") })
        val result = openAsyncAndAwait(RouterContract.PATH_SECOND).assertDelivered()

        assertTrue("应为 Blocked，实际=$result", result is TRouterResult.Blocked)
        assertEquals("异步风控未通过", (result as TRouterResult.Blocked).reason)
        assertEquals("Blocked 不应触发 onLost", 0, lostPaths.size)
    }

    /** B-3：异步 Redirect → 打开改写后的目标。 */
    @Test
    fun asyncRedirectOpensRewrittenTarget() {
        // 只对 /second 改道；改道后的 /about 正常放行（否则会撞上防环上限，见下一条用例）
        reInitWith(
            ControlledAsync("异步改道") { chain ->
                if (chain.meta.path == RouterContract.PATH_SECOND) {
                    chain.redirect(RouterContract.PATH_ABOUT)
                } else {
                    chain.proceed()
                }
            },
        )
        val result = openAsyncAndAwait(RouterContract.PATH_SECOND).assertDelivered()

        assertTrue("应为 Success，实际=$result", result is TRouterResult.Success)
        assertEquals(RouterContract.PATH_ABOUT, (result as TRouterResult.Success).meta.path)
        assertTrue("应有 Redirect 出口日志", logs.lines.any { it.contains("result=Redirect") })
    }

    /** B-3b：异步改道成环 → 被跳数上限截断为 Blocked（防死循环对异步同样生效）。 */
    @Test
    fun asyncRedirectLoopIsCapped() {
        reInitWith(
            ControlledAsync("异步成环") { chain ->
                val next = if (chain.meta.path == RouterContract.PATH_SECOND) {
                    RouterContract.PATH_ABOUT
                } else {
                    RouterContract.PATH_SECOND
                }
                chain.redirect(next)
            },
        )
        val result = openAsyncAndAwait(RouterContract.PATH_SECOND).assertDelivered()

        assertTrue("应为 Blocked，实际=$result", result is TRouterResult.Blocked)
        assertTrue(
            "reason 应指出重定向成环，实际=${(result as TRouterResult.Blocked).reason}",
            result.reason.contains("redirect loop"),
        )
    }

    /** B-4：超时收口，且迟到放行被静默忽略。 */
    @Test
    fun asyncTimeoutIsBlockedAndLateProceedIgnored() {
        reInitWith(
            ControlledAsync("超时放行") { chain -> postDelayed(900) { chain.proceed() } },
            timeoutMs = 200L,
        )
        val sink = openAsyncAndAwait(RouterContract.PATH_SECOND)
        val result = sink.assertDelivered()

        assertTrue("应为 Blocked，实际=$result", result is TRouterResult.Blocked)
        assertTrue(
            "reason 应包含超时信息，实际=${(result as TRouterResult.Blocked).reason}",
            result.reason.contains("异步拦截器超时"),
        )
        assertTrue("应有超时日志", logs.lines.any { it.contains("[interceptor][async][timeout]") })

        // 迟到放行：既不打开页面，也不产生第二次回调（静默忽略，不抛错到 Handler）
        Thread.sleep(1_000)
        assertEquals("迟到放行不得产生第二次回调", 1, sink.count.get())
    }

    /** B-5：取消 → Blocked（已取消），迟到放行被忽略。 */
    @Test
    fun cancelDeliversBlockedAndLateProceedIgnored() {
        reInitWith(ControlledAsync("延时放行") { chain -> postDelayed(300) { chain.proceed() } })
        val sink = Sink()
        val request = TRouter.navigateAsync(RouterContract.PATH_SECOND, null, sink.onResult)
        request.cancel()

        val result = sink.assertDelivered()
        assertTrue("应为 Blocked，实际=$result", result is TRouterResult.Blocked)
        assertTrue(
            "reason 应说明已取消，实际=${(result as TRouterResult.Blocked).reason}",
            result.reason.contains("已取消"),
        )
        assertTrue(request.isCancelled)
        Thread.sleep(600)
        assertEquals("取消后迟到放行不得产生第二次回调", 1, sink.count.get())
    }

    /** B-6：同一异步成员重复终止 → IllegalStateException（配置错误显性化）。 */
    @Test
    fun duplicateTerminationThrowsIllegalState() {
        val recorded = AtomicInteger(0)
        val bad = ControlledAsync("重复终止") { chain ->
            chain.proceed() // 第一次终止：合法
            try {
                chain.block("第二次终止应当被拒绝")
            } catch (e: IllegalStateException) {
                recorded.incrementAndGet()
            }
        }
        // 第二个异步成员保证链在两次终止之间尚未收口（否则迟到终止按静默忽略处理）
        reInitWith(bad, ControlledAsync("后续放行") { chain -> postDelayed(150) { chain.proceed() } })
        val result = openAsyncAndAwait(RouterContract.PATH_SECOND).assertDelivered()

        assertTrue("应为 Success，实际=$result", result is TRouterResult.Success)
        assertEquals("重复终止必须抛 IllegalStateException", 1, recorded.get())
    }

    /** B-7：同步 navigate 遇到**延迟放行**的异步成员 → 明确 Blocked，绝不阻塞等待。 */
    @Test
    fun syncNavigateRejectsDeferredAsyncMember() {
        reInitWith(ControlledAsync("延迟放行") { chain -> postDelayed(200) { chain.proceed() } })
        val result = TRouter.navigate(RouterContract.PATH_SECOND)

        assertTrue("应为 Blocked，实际=$result", result is TRouterResult.Blocked)
        val reason = (result as TRouterResult.Blocked).reason
        assertTrue("reason 应指向 navigateAsync，实际=$reason", reason.contains("navigateAsync"))
        assertTrue("应有同步模式拒绝日志", logs.lines.any { it.contains("[interceptor][async][deferred-sync]") })
    }

    /** B-7b：同步 navigate 遇到**立即放行**的异步成员 → 照常打开（不误伤"链上有异步实现但本次立即通过"）。 */
    @Test
    fun syncNavigateAllowsImmediatelyPassingAsyncMember() {
        val events = mutableListOf<String>()
        reInitWith(
            ControlledAsync("立即放行") { chain ->
                events.add("async:${chain.meta.path}")
                chain.proceed { outcome -> events.add("after:${outcome.javaClass.simpleName}") }
            },
        )
        val result = TRouter.navigate(RouterContract.PATH_SECOND)

        assertTrue("应为 Success，实际=$result", result is TRouterResult.Success)
        assertEquals(
            listOf("async:${RouterContract.PATH_SECOND}", "after:Opened"),
            events,
        )
    }

    /** B-8：异步成员与同步成员顺序一致（同步在前、异步在后）。 */
    @Test
    fun asyncMemberKeepsChainOrder() {
        val events = mutableListOf<String>()
        val syncMember = object : RouteInterceptor {
            override fun intercept(meta: RouteMeta, bundle: android.os.Bundle?): InterceptorDecision {
                events.add("sync:${meta.path}")
                return InterceptorDecision.Continue
            }
        }
        val asyncMember = ControlledAsync("异步成员") { chain ->
            events.add("async:${chain.meta.path}")
            chain.proceed { outcome -> events.add("after:${outcome.javaClass.simpleName}") }
        }
        reInitWith(syncMember, asyncMember)
        val result = openAsyncAndAwait(RouterContract.PATH_SECOND).assertDelivered()

        assertTrue("应为 Success，实际=$result", result is TRouterResult.Success)
        assertEquals(
            listOf("sync:${RouterContract.PATH_SECOND}", "async:${RouterContract.PATH_SECOND}", "after:Opened"),
            events,
        )
    }

    /** B-9：链中可连续出现多个异步成员（各自独立超时窗口）。 */
    @Test
    fun multipleAsyncMembersBothProceed() {
        reInitWith(
            ControlledAsync("异步一") { chain -> postDelayed(60) { chain.proceed() } },
            ControlledAsync("异步二") { chain -> postDelayed(60) { chain.proceed() } },
            timeoutMs = 2_000L,
        )
        val result = openAsyncAndAwait(RouterContract.PATH_SECOND).assertDelivered()

        assertTrue("应为 Success，实际=$result", result is TRouterResult.Success)
        assertEquals(2, logs.lines.count { it.contains("[interceptor][async][wait]") })
    }

    /** B-10：洋葱包裹拦截器不能跨异步成员 → 明确 Blocked。 */
    @Test
    fun wrappingInterceptorCannotWrapAsyncMember() {
        val wrapper = object : WrappingInterceptor {
            override fun intercept(chain: InterceptorChain): ChainOutcome = chain.proceed()
        }
        reInitWith(wrapper, ControlledAsync("异步成员") { chain -> chain.proceed() })
        val result = openAsyncAndAwait(RouterContract.PATH_SECOND).assertDelivered()

        assertTrue("应为 Blocked，实际=$result", result is TRouterResult.Blocked)
        assertTrue(
            "reason 应说明洋葱不能包裹异步，实际=${(result as TRouterResult.Blocked).reason}",
            result.reason.contains("洋葱拦截器不能包裹异步拦截器"),
        )
    }

    // ------------------------------------------------------------------ 脚手架

    /** 可编程异步拦截器：行为由用例注入，避免每个用例写一个类。 */
    private class ControlledAsync(
        private val label: String,
        private val action: (AsyncChain) -> Unit,
    ) : AsyncInterceptor {
        override fun intercept(chain: AsyncChain) = action(chain)
        override fun toString(): String = label
    }

    /** 结果收集器：单次回调 + 线程记录。 */
    private class Sink {
        private val latch = CountDownLatch(1)
        val count = AtomicInteger(0)

        @Volatile
        var result: TRouterResult? = null

        @Volatile
        var onMainThread: Boolean = false

        val onResult: (TRouterResult) -> Unit = { r ->
            count.incrementAndGet()
            result = r
            onMainThread = Looper.myLooper() === Looper.getMainLooper()
            latch.countDown()
        }

        fun assertDelivered(): TRouterResult {
            assertTrue("3 秒内未收到结果回调", latch.await(3, TimeUnit.SECONDS))
            val r = result
            assertNotNull("结果不应为 null", r)
            return r!!
        }
    }

    private fun openAsyncAndAwait(path: String): Sink {
        val sink = Sink()
        TRouter.navigateAsync(path, null, sink.onResult)
        return sink
    }

    /** 主线程延时执行（模拟异步拦截器在别处拿到结果后回调）。 */
    private fun postDelayed(delayMs: Long, block: () -> Unit) {
        android.os.Handler(Looper.getMainLooper()).postDelayed(block, delayMs)
    }

    private fun reInitWith(vararg members: RouteChainMember, timeoutMs: Long = 5_000L) {
        reInit(
            TestConfig(
                isDebug = true,
                logSink = logs,
                onLost = { lostPaths += it },
                interceptors = members.toList(),
                asyncInterceptorTimeoutMs = timeoutMs,
            ),
        )
    }
}
