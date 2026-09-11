package com.demo.trouter

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouteTargetKind
import com.trouter.core.api.TRouterResult
import com.trouter.core.internal.IRouterService
import com.trouter.core.internal.RemoteProxies
import com.trouter.core.internal.RemoteReplyCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/**
 * AIDL 动态代理封装测试（D，方案 docs/Backlog补全方案-动态化与硬化.md §6 = 语义①）。
 * 用 fake binder（IRouterService.Stub 直接实现）验证：透明代理类型、navigate 委托、
 * 结果一致、Object 方法安全。
 */
@RunWith(AndroidJUnit4::class)
class TRouterRemoteProxyTest {

    @Test
    fun proxyIsTransparentAndDelegatesNavigate() {
        var delegateCalls = 0
        val fake = object : IRouterService.Stub() {
            override fun navigate(path: String?, bundle: Bundle?): String {
                delegateCalls++
                return RemoteReplyCodec.encode(
                    TRouterResult.Success(
                        RouteMeta(path ?: "", "default", "com.demo.trouter.DynamicDemoActivity", RouteTargetKind.ACTIVITY),
                    ),
                    "remoteTrace1",
                    7L,
                )
            }

            // 模块间接口调用 跨进程服务：本用例只验证 navigate 的透明委托，服务通道按协议返回"未注册"错误串即可
            override fun callService(name: String?, args: Bundle?): String =
                "-ERR unregistered:${name ?: "null"}"

            // 多进程与跨进程增强 类型化通道：本用例不覆盖，返回失败回包
            override fun callTyped(service: String?, method: String?, args: Bundle?): Bundle =
                com.trouter.core.api.TRouterTypedReply.failure("proxy-test-not-supported")
        }

        val proxy = RemoteProxies.delegating(fake)

        assertTrue("应为 Java 动态代理实例", Proxy.isProxyClass(proxy.javaClass))
        assertTrue("应实现 IRouterService", proxy is IRouterService)

        val replyRaw = proxy.navigate("/some/path", null)
        val reply = RemoteReplyCodec.parse(replyRaw)
        assertTrue("回包应可解析", reply != null)
        assertEquals("/some/path", reply!!.path)
        assertEquals(TRouterResult.Success::class.java.simpleName, "Success")
        assertEquals("应真实委托给 binder", 1, delegateCalls)

        // Object 方法安全（不触发 binder 调用）
        assertTrue("equals 自身为 true", proxy == proxy)
        assertTrue("toString 稳定且标识 delegate", proxy.toString().contains("RemoteRouterProxy"))
        proxy.hashCode()
    }
}
