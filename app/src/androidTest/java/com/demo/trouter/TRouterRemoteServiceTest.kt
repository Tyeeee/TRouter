package com.demo.trouter

import android.content.ComponentName
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterConfig
import com.trouter.core.internal.RemoteRouterService
import com.trouter.core.testing.BaseTRouterTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 跨进程接口调用 跨进程服务测试：真实 AIDL 双进程调用远端进程注册的端点。
 */
@RunWith(AndroidJUnit4::class)
class TRouterRemoteServiceTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    private fun awaitString(timeoutMs: Long = 20_000, call: (onResult: (String) -> Unit) -> Unit): String {
        val latch = CountDownLatch(1)
        var boxed: String? = null
        call { s ->
            boxed = s
            latch.countDown()
        }
        assertTrue("跨进程服务回调超时", latch.await(timeoutMs, TimeUnit.MILLISECONDS))
        return boxed!!
    }

    /** 跨进程接口调用-1：调用远端端点（仅 :remote 注册）→ 返回结果含参数，且执行进程 ≠ host。 */
    @Test
    fun remoteEndpointReturnsResultFromRemoteProcess() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        reInit(
            TRouterConfig(
                isDebug = true,
                logSink = logs,
                onLost = { lostPaths += it },
                remoteService = ComponentName(ctx, RemoteRouterService::class.java),
            ),
        )
        val hostPid = Process.myPid()
        val reply = awaitString { cb ->
            TRouter.callRemoteService(
            "demoClock",
            Bundle().apply { putString("q", "跨进程服务参数") },
            onResult = cb,
        )
        }
        assertTrue("应命中远端端点: $reply", reply.startsWith("clock-v1"))
        assertTrue("参数应原样到达远端: $reply", reply.contains("q=跨进程服务参数"))
        val remotePid = Regex("pid=(\\d+)").find(reply)?.groupValues?.get(1)?.toIntOrNull()
        assertTrue("回包应含远端 pid: $reply", remotePid != null)
        assertNotEquals("服务应真实在 :remote 进程执行", hostPid, remotePid)
        assertTrue("日志应有 service recv",
            logs.lines.joinToString("\n").contains("[remote][service][recv]") && logs.lines.joinToString("\n").contains("name=demoClock"))
    }

    /** 跨进程接口调用-2：未注册端点 → 明确错误串（非静默）。 */
    @Test
    fun unregisteredEndpointReturnsError() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        reInit(
            TRouterConfig(
                isDebug = true,
                logSink = logs,
                remoteService = ComponentName(ctx, RemoteRouterService::class.java),
            ),
        )
        val reply = awaitString { cb -> TRouter.callRemoteService("noSuchEndpoint", null, onResult = cb) }
        assertTrue("应返回错误串: $reply", reply.startsWith("-ERR "))
        assertTrue("错误原因含 unregistered", reply.contains("unregistered"))
    }

    /** 跨进程接口调用-3：未配置 remoteService → 立即错误串。 */
    @Test
    fun serviceWithoutRemoteConfiguredReturnsError() {
        reInit(
            TRouterConfig(isDebug = true, logSink = logs),
        )
        val reply = awaitString { cb -> TRouter.callRemoteService("demoClock", null, onResult = cb) }
        assertTrue("应返回错误串: $reply", reply.startsWith("-ERR "))
    }
}
