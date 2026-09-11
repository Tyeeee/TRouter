package com.demo.trouter

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.demo.trouter.generated.CrossProcessPaths
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterConfig
import com.trouter.core.api.TRouterResult
import com.trouter.core.internal.RemoteRouterService
import com.trouter.core.testing.BaseTRouterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 跨进程通道契约测试（场景 S12，见 docs/V4.0-跨进程路由方案.md §5）。
 *
 * 说明：S11（成功链路 UI）由 MainRouterTest#testRemoteProcessNavigation 覆盖；
 * 本类针对**跨进程契约分支**直调 TRouter.navigateRemote，均走**真实 AIDL 双进程**
 * （不 mock）：白名单拦截、服务未配置、远端 NotFound 映射、Success 与 traceId 关联。
 * 每个用例 reInit 注入用例私有配置；远端服务进程由系统按需拉起（Application 在各进程独立 init）。
 */
@RunWith(AndroidJUnit4::class)
class TRouterRemoteContractTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    private fun remoteService(): ComponentName {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ComponentName(ctx, RemoteRouterService::class.java)
    }

    private fun remoteConfig(whitelist: Set<String>?, service: ComponentName? = remoteService()) =
        TRouterConfig(
            isDebug = true,
            logSink = logs,
            onLost = { lostPaths += it },
            remoteService = service,
            remoteWhitelist = whitelist,
        )

    /** 驱动一次 navigateRemote 并同步等待其（可能异步的）结果。 */
    private fun awaitRemote(timeoutMs: Long = 20_000, call: (onResult: (TRouterResult) -> Unit) -> Unit): TRouterResult {
        val latch = CountDownLatch(1)
        var boxed: TRouterResult? = null
        call { result ->
            boxed = result
            latch.countDown()
        }
        assertTrue("跨进程回调超时（${timeoutMs}ms）", latch.await(timeoutMs, TimeUnit.MILLISECONDS))
        return boxed!!
    }

    /** S12-1（A3 语义）：remoteService 未配置 → 立即 Blocked("未配置")，不 bind、不触发 onLost。 */
    @Test
    fun remoteUnconfiguredReturnsBlockedImmediately() {
        reInit(remoteConfig(whitelist = CrossProcessPaths.paths, service = null))

        val result = awaitRemote { cb -> TRouter.navigateRemote(RouterContract.PATH_REMOTE_SECOND, null, onResult = cb) }

        assertTrue("应返回 Blocked: $result", result is TRouterResult.Blocked)
        assertTrue("reason 应含未配置: ${(result as TRouterResult.Blocked).reason}", result.reason.contains("未配置"))
        assertTrue("onLost 不应触发", lostPaths.isEmpty())
        assertTrue("日志应含 fail", logs.lines.joinToString("\n").contains("[remote][fail]"))
    }

    /** S12-2（C4 白名单）：未标注 @CrossProcess 的 path 被 host 通道拒绝 → Blocked，不发往远端。 */
    @Test
    fun remoteWhitelistBlocksUnlistedPath() {
        reInit(remoteConfig(whitelist = CrossProcessPaths.paths)) // 白名单仅 /remote-second

        val result = awaitRemote { cb -> TRouter.navigateRemote(RouterContract.PATH_SECOND, null, onResult = cb) }

        assertTrue("应返回 Blocked: $result", result is TRouterResult.Blocked)
        assertTrue(
            "reason 应含 @CrossProcess: ${(result as TRouterResult.Blocked).reason}",
            result.reason.contains("@CrossProcess"),
        )
        assertTrue("onLost 不应触发", lostPaths.isEmpty())
    }

    /** S12-3（C3 NotFound 映射）：白名单放行的未注册 path → 远端 NotFound，host 侧 onLost 不被触发。 */
    @Test
    fun remoteNotFoundIsMappedWithoutHostOnLost() {
        reInit(remoteConfig(whitelist = CrossProcessPaths.paths + RouterContract.PATH_UNREGISTERED))

        val result = awaitRemote { cb -> TRouter.navigateRemote(RouterContract.PATH_UNREGISTERED, null, onResult = cb) }

        assertEquals(TRouterResult.NotFound(RouterContract.PATH_UNREGISTERED), result)
        assertTrue("远端 NotFound 不应触发 host onLost", lostPaths.isEmpty())
        val join = logs.lines.joinToString("\n")
        assertTrue("recv 应记录 NotFound:\n$join",
            join.contains("[remote][recv]") && join.contains("result=NotFound(path=${RouterContract.PATH_UNREGISTERED}"))
    }

    /** S12-5（C1 回归）：服务已连接后**连续第二次** navigateRemote 必须立即派发（曾因只靠 onServiceConnected
     *  派发、连接态无触发点而一直等到超时——真实复现于「成功一次→返回→再点」场景，本次为自动化回归锁死）。 */
    @Test
    fun secondRemoteCallReusesConnectedService() {
        reInit(remoteConfig(whitelist = CrossProcessPaths.paths))

        val first = awaitRemote { cb -> TRouter.navigateRemote(RouterContract.PATH_REMOTE_SECOND, null, onResult = cb) }
        assertTrue("首次应 Success: $first", first is TRouterResult.Success)

        // 不 reset：同一客户端、同一条已连接通道上发起第二次调用
        val second = awaitRemote { cb -> TRouter.navigateRemote(RouterContract.PATH_REMOTE_SECOND, null, onResult = cb) }
        assertTrue("第二次应 Success（连接态需主动派发，不得超时）: $second", second is TRouterResult.Success)

        val recvCount = logs.lines.count { it.contains("[remote][recv]") }
        assertEquals("同一连接上应收到两次 recv", 2, recvCount)
        assertTrue("日志不应出现超时失败", logs.lines.none { it.contains("服务连接超时") })
    }

    /** S12-4（C1/C5 traceId）：白名单放行 /remote-second → 远端 Success，send/recv traceId 可互查。 */
    @Test
    fun remoteSuccessAndTraceCorrelation() {
        reInit(remoteConfig(whitelist = CrossProcessPaths.paths))

        val result = awaitRemote { cb -> TRouter.navigateRemote(RouterContract.PATH_REMOTE_SECOND, null, onResult = cb) }

        assertTrue("应返回 Success: $result", result is TRouterResult.Success)
        assertEquals(RouterContract.PATH_REMOTE_SECOND, (result as TRouterResult.Success).meta.path)
        assertTrue("onLost 不应触发", lostPaths.isEmpty())

        val join = logs.lines.joinToString("\n")
        val send = logs.lines.firstOrNull { it.contains("[remote][send]") }
        val recv = logs.lines.firstOrNull { it.contains("[remote][recv]") }
        assertTrue("缺少 send 日志:\n$join", send != null)
        assertTrue("缺少 recv 日志:\n$join", recv != null)
        val sendId = Regex("traceId=([0-9a-f]+)").find(send!!)?.groupValues?.get(1)
        val recvId = Regex("traceId=([0-9a-f]+)").find(recv!!)?.groupValues?.get(1)
        val origin = Regex("origin=([0-9a-f]+)").find(recv)?.groupValues?.get(1)
        assertEquals("recv.origin 应等于 send.traceId", sendId, origin)
        assertTrue("远端应有独立 traceId 且不等于 host 侧: $recvId vs $sendId", recvId != null && recvId != sendId)
    }
}
