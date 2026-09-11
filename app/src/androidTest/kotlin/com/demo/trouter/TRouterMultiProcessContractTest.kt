package com.demo.trouter

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.demo.trouter.generated.CrossProcessPaths
import com.trouter.core.api.DemoParams
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterResult
import com.trouter.core.api.TRouterConfig
import com.trouter.core.internal.RemoteRouterService
import com.trouter.core.testing.BaseTRouterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 多进程契约测试（批次 C，场景 S26/S27）——**真跑三个进程**：
 * host（测试所在进程）+ `:remote` + `:remote2`。
 *
 * 覆盖：
 * 1. 经 `target="remote2"` 把页面开到第三个进程，参数经 AIDL 原样到达（回包 params echo 佐证）；
 * 2. **端点表按进程独立**：`demoClock2` 只在 `:remote2` 注册 → 指定 target 可用、默认 target 返回未注册；
 *    反向同理（`demoClock` 只在 `:remote` 注册）；
 * 3. 两个远端进程**并发导航**互不干扰：各自 Success，且 send 日志里的 targetProcess 不同；
 * 4. 未配置的 target → 明确 Blocked（reason 指向 remoteServices）；
 * 5. 白名单对额外进程同样生效（未标 @CrossProcess 的 path 不允许走 :remote2）。
 */
@RunWith(AndroidJUnit4::class)
class TRouterMultiProcessContractTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    /** 两个远端进程都配齐（默认 :remote + 额外 remote2）。 */
    private fun reInitWithBothProcesses() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        reInit(
            TRouterConfig(
                isDebug = true,
                logSink = logs,
                onLost = { lostPaths += it },
                remoteService = ComponentName(ctx, RemoteRouterService::class.java),
                remoteWhitelist = CrossProcessPaths.paths,
                remoteServices = mapOf(
                    TRouterDemoApp.REMOTE_TARGET_SECOND to
                        ComponentName(ctx, RemoteRouterServiceSecond::class.java),
                ),
            ),
        )
    }

    /** 用例 1：跨进程导航到第三个进程 + 参数原样到达。 */
    @Test
    fun navigateToThirdProcessOpensPageWithParams() {
        reInitWithBothProcesses()
        val bundle = Bundle().apply {
            putString(DemoParams.KEY_MSG, "三进程参数")
            putInt(DemoParams.KEY_COUNT, 3)
        }
        val sink = RemoteSink()
        TRouter.navigateRemote(
            RouterContract.PATH_REMOTE_THIRD,
            bundle,
            TRouterDemoApp.REMOTE_TARGET_SECOND,
            sink.onResult,
        )

        val result = sink.await()
        assertTrue("应为 Success，实际=$result", result is TRouterResult.Success)
        assertEquals(RouterContract.PATH_REMOTE_THIRD, (result as TRouterResult.Success).meta.path)

        val join = waitForLog { it.contains("[remote][recv]") && it.contains("params=[") }
        assertTrue("recv 应携带参数回显:\n$join", join.contains("${DemoParams.KEY_MSG}=三进程参数"))
        assertTrue("recv 应是 /remote-third 的成功:\n$join",
            join.contains("result=Success(meta=${RouterContract.PATH_REMOTE_THIRD})"))
        assertTrue("send 日志应指向 remote2 服务组件:\n$join",
            join.contains("[remote][send]") && join.contains("RemoteRouterServiceSecond"))
    }

    /** 用例 2：端点表按进程独立（正向）。 */
    @Test
    fun endpointRegisteredOnlyInThirdProcessIsReachableThere() {
        reInitWithBothProcesses()
        val args = Bundle().apply { putString("q", "hello") }
        val sink = RemoteStringSink()
        TRouter.callRemoteService("demoClock2", args, TRouterDemoApp.REMOTE_TARGET_SECOND, sink.onResult)

        val reply = sink.await()
        assertTrue(":remote2 上的 demoClock2 应正常返回，实际=$reply", reply.contains("clock2-v1"))
        assertTrue("返回应携带 :remote2 的 pid，实际=$reply", reply.contains("pid="))
    }

    /** 用例 3：端点表按进程独立（反向——默认 :remote 上并没有 demoClock2）。 */
    @Test
    fun endpointIsInvisibleFromOtherProcess() {
        reInitWithBothProcesses()
        val args = Bundle().apply { putString("q", "hello") }
        val sink = RemoteStringSink()
        TRouter.callRemoteService("demoClock2", args, null, sink.onResult)

        val reply = sink.await()
        assertTrue("默认进程(:remote)上不应存在 demoClock2，实际=$reply", reply.startsWith("-ERR"))
        assertTrue("错误串应说明未注册，实际=$reply", reply.contains("unregistered"))
        assertTrue("调用方应能识别错误串", TRouter.isRemoteEndpointError(reply))
    }

    /** 用例 4：两个远端进程并发导航互不干扰。 */
    @Test
    fun concurrentNavigationToBothProcessesIsIndependent() {
        reInitWithBothProcesses()
        val first = RemoteSink()
        val third = RemoteSink()

        // 同时发起：默认 :remote 与 target=remote2
        TRouter.navigateRemote(RouterContract.PATH_REMOTE_SECOND, null, null, first.onResult)
        TRouter.navigateRemote(
            RouterContract.PATH_REMOTE_THIRD,
            null,
            TRouterDemoApp.REMOTE_TARGET_SECOND,
            third.onResult,
        )

        val r1 = first.await()
        val r3 = third.await()
        assertTrue(":remote 应 Success，实际=$r1", r1 is TRouterResult.Success)
        assertTrue(":remote2 应 Success，实际=$r3", r3 is TRouterResult.Success)
        assertEquals(RouterContract.PATH_REMOTE_SECOND, (r1 as TRouterResult.Success).meta.path)
        assertEquals(RouterContract.PATH_REMOTE_THIRD, (r3 as TRouterResult.Success).meta.path)

        val join = waitForLog { it.contains("RemoteRouterServiceSecond") }
        assertTrue("两条 send 日志应分别指向两个不同服务组件:\n$join",
            join.contains("RemoteRouterServiceSecond") && join.contains("core.internal.RemoteRouterService"))
    }

    /** 用例 5：未配置的 target → 明确 Blocked。 */
    @Test
    fun unconfiguredTargetIsBlocked() {
        reInitWithBothProcesses()
        val sink = RemoteSink()
        TRouter.navigateRemote(RouterContract.PATH_REMOTE_THIRD, null, "ghost", sink.onResult)

        val result = sink.await()
        assertTrue("应为 Blocked，实际=$result", result is TRouterResult.Blocked)
        val reason = (result as TRouterResult.Blocked).reason
        assertTrue("reason 应指向 remoteServices，实际=$reason", reason.contains("remoteServices"))
    }

    /** 用例 6：白名单对额外进程同样生效（/about 未标 @CrossProcess）。 */
    @Test
    fun whitelistAppliesToExtraProcessToo() {
        reInitWithBothProcesses()
        val sink = RemoteSink()
        TRouter.navigateRemote(RouterContract.PATH_ABOUT, null, TRouterDemoApp.REMOTE_TARGET_SECOND, sink.onResult)

        val result = sink.await()
        assertTrue("应为 Blocked，实际=$result", result is TRouterResult.Blocked)
        val reason = (result as TRouterResult.Blocked).reason
        assertTrue("reason 应说明未标注 @CrossProcess，实际=$reason", reason.contains("@CrossProcess"))
    }

    // ------------------------------------------------------------------ 脚手架

    private class RemoteSink {
        private val latch = CountDownLatch(1)
        private val ref = AtomicReference<TRouterResult>()

        val onResult: (TRouterResult) -> Unit = { r ->
            ref.set(r)
            latch.countDown()
        }

        fun await(): TRouterResult {
            assertTrue("20 秒内未收到跨进程结果（冷启动较慢）", latch.await(20, TimeUnit.SECONDS))
            return ref.get()!!
        }
    }

    private class RemoteStringSink {
        private val latch = CountDownLatch(1)
        private val ref = AtomicReference<String>()

        val onResult: (String) -> Unit = { r ->
            ref.set(r)
            latch.countDown()
        }

        fun await(): String {
            assertTrue("20 秒内未收到跨进程服务回包", latch.await(20, TimeUnit.SECONDS))
            return ref.get()!!
        }
    }

    /** 轮询等待日志条件（跨进程冷启动可达数十秒，故给 20s）。 */
    private fun waitForLog(condition: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val join = logs.lines.joinToString("\n")
            if (condition(join)) return join
            Thread.sleep(200)
        }
        return logs.lines.joinToString("\n")
    }
}
