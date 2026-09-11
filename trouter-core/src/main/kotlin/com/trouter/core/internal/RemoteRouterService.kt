package com.trouter.core.internal

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import com.trouter.core.api.TRouter
import java.util.UUID

/**
 * 跨进程导航服务（V4.0，remote 进程侧）。
 *
 * 宿主在 manifest 中把本组件声明到独立进程（如 android:process=":remote"）；
 * remote 进程的 Application.onCreate 会各自 init/install 一份 TRouter，
 * 因此本服务直接调用 TRouter.navigate 即由**远端进程自己的路由表**解析并打开远端页面，
 * 再把结果摘要（RemoteReplyCodec）返回 host。
 *
 * 多进程（批次 C）：本类为 **open**，宿主需要第二个跨进程进程时，
 * 声明一个子类并在 manifest 里指定 `android:process=":remote2"` 即可（同一个类不能声明两次）。
 */
open class RemoteRouterService : Service() {

    private val binder = object : IRouterService.Stub() {
        override fun navigate(path: String?, bundle: Bundle?): String {
            val startMs = SystemClock.elapsedRealtime()
            val remoteTraceId = UUID.randomUUID().toString().replace("-", "").take(8)
            val result = TRouter.navigate(path ?: "", bundle)
            val costMs = SystemClock.elapsedRealtime() - startMs
            // 传输层参数回显：把收到的 bundle 基础类型摘要带回 host，供“参数确实跨进程送达”的自动化校验
            return RemoteReplyCodec.encode(result, remoteTraceId, costMs, paramEcho(bundle))
        }

        override fun callService(name: String?, args: Bundle?): String {
            // G2-remote：调用远端进程内注册的服务端点（端点返回结构化字符串）
            return TRouter.invokeRemoteEndpoint(name ?: "", args ?: Bundle())
        }

        override fun callTyped(service: String?, method: String?, args: Bundle?): Bundle {
            // 批次 C：类型化调用——由 @RemoteApi 生成物登记的分发器处理，结果仍走原生 Bundle
            return TRouter.invokeRemoteApi(service ?: "", method ?: "", args ?: Bundle())
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        /** bundle 基础类型有序摘要（String/数值/布尔；单个值截断，防止回包被用户长文本撑爆）。 */
        private const val MAX_ECHO_ENTRIES = 100
        private const val MAX_VALUE_LEN = 200

        private fun paramEcho(bundle: Bundle?): List<String> {
            if (bundle == null) return emptyList()
            val out = ArrayList<String>()
            val keys = bundle.keySet()?.sorted() ?: emptyList()
            for (key in keys) {
                if (out.size >= MAX_ECHO_ENTRIES) break
                val value = bundle.get(key)
                val text = when (value) {
                    is String -> value
                    is CharSequence -> value.toString()
                    is Int, is Long, is Boolean, is Double, is Float -> value.toString()
                    else -> null
                } ?: continue
                out.add("$key=${text.take(MAX_VALUE_LEN)}")
            }
            return out
        }
    }
}
