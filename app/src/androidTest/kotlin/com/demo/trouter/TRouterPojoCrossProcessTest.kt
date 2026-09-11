package com.demo.trouter

import android.content.ComponentName
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.demo.trouter.generated.CrossProcessPaths
import com.demo.trouter.generated.TRouterPojoRegistry
import com.demo.trouter.generated.TRouterPojo_DemoInner
import com.demo.trouter.generated.TRouterPojo_DemoReport
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterConfig
import com.trouter.core.api.TRouterPojoCodec
import com.trouter.core.internal.RemoteRouterService
import com.trouter.core.testing.BaseTRouterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * POJO 跨进程编解码测试（多进程与跨进程增强 第 2 部分，场景 S28）。
 *
 * 覆盖：
 * 1. **本地往返**：pack → unpack 与原对象完全相等（含 List / 枚举 / 非空嵌套 POJO）；
 * 2. **null 语义**：可空嵌套 POJO 为 null 时往返后仍为 null（`__null` 标志位）；
 * 3. **注册表**：可按类名取到 codec，并通过 `TRouterPojoCodec` 通用接口完成打包/还原
 *    （这是后续"类型化远程服务代理"的框架侧入口）；
 * 4. **真实跨进程**：POJO 打包进 Bundle → AIDL 送到 `:remote2` → 远端用生成的 codec 解包并回显字段，
 *    证明业务对象**不需要手写 Parcelable** 也能跨进程。
 */
@RunWith(AndroidJUnit4::class)
class TRouterPojoCrossProcessTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    private fun sample(): DemoReport = DemoReport(
        id = "R-2026",
        count = 7,
        ok = true,
        tags = listOf("alpha", "beta"),
        inner = DemoInner("内层对象", DemoLevel.HIGH),
    )

    /** C-1：本地往返完全相等。 */
    @Test
    fun pojoRoundTripsLocally() {
        val report = sample()
        val bundle = Bundle().apply { TRouterPojo_DemoReport.pack(this, report) }
        assertEquals("pack/unpack 应完全相等", report, TRouterPojo_DemoReport.unpack(bundle))
    }

    /** C-2：可空嵌套 POJO 的 null 语义。 */
    @Test
    fun nullableNestedPojoKeepsNull() {
        val report = sample().copy(inner = null)
        val bundle = Bundle().apply { TRouterPojo_DemoReport.pack(this, report) }
        val restored = TRouterPojo_DemoReport.unpack(bundle)
        assertNull("null 嵌套字段往返后应仍为 null", restored.inner)
        assertEquals(report, restored)
    }

    /** C-3：注册表按类名提供 codec，且通用接口可用（框架侧入口）。 */
    @Test
    fun registryExposesCodecByNameAndWorksGenerically() {
        val codec: TRouterPojoCodec? = TRouterPojoRegistry.codecOf(DemoReport::class.java.name)
        assertNotNull("注册表应能按类名取到 codec", codec)

        val report = sample()
        val bundle = Bundle()
        codec!!.pack(bundle, report)
        assertEquals("通过通用接口 pack/unpack 也应相等", report, codec.unpack(bundle))
        assertEquals("嵌套 POJO 也应在注册表里", 2, TRouterPojoRegistry.all().size)
        assertEquals(
            DemoInner::class.java.name,
            TRouterPojo_DemoInner.className,
        )
    }

    /** C-4：真实跨进程——POJO 经 AIDL 到达 :remote2 并由远端解包回显。 */
    @Test
    fun pojoTravelsAcrossProcessAndUnpacksRemotely() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
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

        val report = sample()
        val bundle = Bundle().apply { TRouterPojo_DemoReport.pack(this, report) }

        val latch = CountDownLatch(1)
        val ref = AtomicReference<String>()
        TRouter.callRemoteService("pojoEcho", bundle, TRouterDemoApp.REMOTE_TARGET_SECOND) { reply ->
            ref.set(reply)
            latch.countDown()
        }
        assertTrue("20 秒内未收到远端回包（冷启动较慢）", latch.await(20, TimeUnit.SECONDS))

        val reply = ref.get()
        assertTrue("远端应成功解包并回显，实际=$reply", reply.contains("pojoEcho ✓"))
        assertTrue("String 字段应到达，实际=$reply", reply.contains("id=${report.id}"))
        assertTrue("Int 字段应到达，实际=$reply", reply.contains("count=${report.count}"))
        assertTrue("Boolean 字段应到达，实际=$reply", reply.contains("ok=true"))
        assertTrue("List 字段应到达，实际=$reply", reply.contains("tags=alpha|beta"))
        assertTrue("嵌套 POJO 应到达，实际=$reply", reply.contains("inner=内层对象/HIGH"))
        assertTrue("回包应带远端 pid，实际=$reply", reply.contains("pid="))
    }
}
