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
 */
class RemoteRouterService : Service() {

    private val binder = object : IRouterService.Stub() {
        override fun navigate(path: String?, bundle: Bundle?): String {
            val startMs = SystemClock.elapsedRealtime()
            val remoteTraceId = UUID.randomUUID().toString().replace("-", "").take(8)
            val result = TRouter.navigate(path ?: "", bundle)
            val costMs = SystemClock.elapsedRealtime() - startMs
            return RemoteReplyCodec.encode(result, remoteTraceId, costMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
