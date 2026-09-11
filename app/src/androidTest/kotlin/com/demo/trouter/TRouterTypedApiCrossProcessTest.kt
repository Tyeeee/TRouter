package com.demo.trouter

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.demo.trouter.generated.CrossProcessPaths
import com.demo.trouter.generated.TRouterRemoteApiRegistry
import com.demo.trouter.generated.TRouterRemoteApi_DemoStatsApi
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterConfig
import com.trouter.core.internal.RemoteRouterService
import com.trouter.core.testing.BaseTRouterTest
import com.trouter.core.testing.TestConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 类型化远程接口测试（多进程与跨进程增强 第 2 部分，场景 S29）。
 *
 * 覆盖：
 * 1. 基础类型参数/结果：`count("abcd")` → 远端返回 8（真跨进程，回包带远端 pid 的调用另行校验）；
 * 2. 多参数 + 枚举 + `List<Int>`：`summarize(...)` → 远端汇总字符串（含 pid）；
 * 3. **结果类型是 @RemotePojo**：`report("X")` → 返回的 DemoReport 字段与远端实现的完全一致；
 * 4. 错误路径：调用方注册了编解码器、但目标进程没有登记实现 → 走 `onError`（不再静默）；
 * 5. 代理的 Object 方法（toString/equals/hashCode）在本地处理，不触发跨进程调用。
 */
@RunWith(AndroidJUnit4::class)
class TRouterTypedApiCrossProcessTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

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
        // 客户端编解码器注册（与 demo Application 中的接线一致）
        TRouter.registerRemoteApiClients(TRouterRemoteApiRegistry.all())
    }

    /** 调用类型化接口，返回 (成功值, 错误原因)。 */
    private fun <T> callTyped(
        target: String?,
        action: (DemoStatsApi, (T) -> Unit, (String) -> Unit) -> Unit,
    ): Pair<T?, String?> {
        val latch = CountDownLatch(1)
        val value = AtomicReference<T?>()
        val error = AtomicReference<String?>()
        val api = TRouter.remoteApi(DemoStatsApi::class.java, target) { reason ->
            error.set(reason)
            latch.countDown()
        }
        action(api, { v ->
            value.set(v)
            latch.countDown()
        }, { e ->
            error.set(e)
            latch.countDown()
        })
        assertTrue("20 秒内未收到类型化调用结果（冷启动较慢）", latch.await(20, TimeUnit.SECONDS))
        return value.get() to error.get()
    }

    /** C-1：基础类型往返。 */
    @Test
    fun typedPrimitiveCallTravelsAcrossProcess() {
        reInitWithBothProcesses()
        val (value, error) = callTyped<Int>(TRouterDemoApp.REMOTE_TARGET_SECOND) { api, ok, _ ->
            api.count("abcd", ok)
        }
        assertEquals("不应有错误", null, error)
        assertEquals("远端应返回 4*2", 8, value)
        assertTrue(
            "应有类型化 recv 日志",
            logs.lines.any { it.contains("[remote][typed][recv]") && it.contains("method=count") },
        )
    }

    /** C-2：多参数 + 枚举 + List<Int>。 */
    @Test
    fun typedMultiParamEnumAndListTravel() {
        reInitWithBothProcesses()
        val (value, error) = callTyped<String>(TRouterDemoApp.REMOTE_TARGET_SECOND) { api, ok, _ ->
            api.summarize("s29", DemoLevel.HIGH, listOf(1, 2, 3), ok)
        }
        assertEquals("不应有错误", null, error)
        assertNotNull(value)
        assertTrue("远端汇总应含 tag，实际=$value", value!!.contains("tag=s29"))
        assertTrue("枚举应跨进程，实际=$value", value.contains("level=HIGH"))
        assertTrue("List 应跨进程求和，实际=$value", value.contains("sum=6"))
        assertTrue("应带远端 pid，实际=$value", value.contains("pid="))
    }

    /** C-3：结果是 @RemotePojo 业务对象。 */
    @Test
    fun typedPojoResultComesBackDecoded() {
        reInitWithBothProcesses()
        val (value, error) = callTyped<DemoReport>(TRouterDemoApp.REMOTE_TARGET_SECOND) { api, ok, _ ->
            api.report("X", ok)
        }
        assertEquals("不应有错误", null, error)
        assertNotNull(value)
        val report = value!!
        assertEquals("remote-X", report.id)
        assertEquals(99, report.count)
        assertTrue(report.ok)
        assertEquals(listOf("remote", "typed"), report.tags)
        assertEquals("远端内层对象", report.inner?.name)
        assertEquals(DemoLevel.HIGH, report.inner?.level)
    }

    /** C-4：目标进程未登记实现 → 走 onError（不静默、不崩溃）。 */
    @Test
    fun unregisteredImplementationGoesToOnError() {
        reInitWithBothProcesses()
        // 默认 target（:remote）没有登记 DemoStatsApi 实现
        val (value, error) = callTyped<Int>(null) { api, ok, _ ->
            api.count("abcd", ok)
        }
        assertEquals("不应有成功值", null, value)
        assertNotNull("应收到错误回调", error)
        assertTrue("错误应说明远端未注册，实际=$error", error!!.contains("未注册"))
        assertTrue("应有类型化 error 日志", logs.lines.any { it.contains("[remote][typed][error]") })
    }

    /** C-5：代理的 Object 方法在本地处理，不触发跨进程调用。 */
    @Test
    fun proxyObjectMethodsAreHandledLocally() {
        reInitWithBothProcesses()
        val api = TRouter.remoteApi(DemoStatsApi::class.java, TRouterDemoApp.REMOTE_TARGET_SECOND) { }
        assertTrue("toString 应标识代理", api.toString().contains("TRouterRemoteApiProxy"))
        assertTrue("equals 自身为 true", api == api)
        api.hashCode()
        assertTrue(
            "Object 方法不应产生跨进程调用",
            logs.lines.none { it.contains("[remote][typed][send]") },
        )
    }

    /** C-6：未注册编解码器时，取代理应直接抛错（配置错误显性化）。 */
    @Test
    fun missingClientCodecThrowsImmediately() {
        reInitWithBothProcesses()
        // 重新初始化但**不注册**客户端编解码器（reInit 会 reset + init + install）
        reInit(TestConfig(isDebug = true, logSink = logs))

        val thrown = runCatching { TRouter.remoteApi(DemoStatsApi::class.java, null) }.exceptionOrNull()
        assertNotNull("未注册编解码器应抛错", thrown)
        assertTrue(
            "错误信息应指引注册方式，实际=${thrown!!.message}",
            thrown.message!!.contains("registerClient"),
        )
    }
}
