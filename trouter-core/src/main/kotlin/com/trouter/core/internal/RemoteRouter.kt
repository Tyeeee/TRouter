package com.trouter.core.internal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.trouter.core.api.TRouterResult
import java.util.UUID

/**
 * host 侧跨进程通道客户端（V4.0，重写版）。
 *
 * 设计原则（回应「不能靠调用顺序保证成功」）：
 * 1. **单后台线程串行处理**：所有操作——入队、连接回调、超时、派发——都投递到同一个
 *    HandlerThread（trouter-remote）上串行执行。不存在并发交错，也就不需要锁与"时序巧合"；
 * 2. **派发由状态驱动，而非调用顺序**：任何可能让待处理请求得以执行的状态迁移
 *    （请求入队后、onServiceConnected 后）都主动调用 dispatch()；请求一旦入队，
 *    只可能走向「连接就绪→真实执行」或「超时/断开→明确 Blocked」，没有"无人派发"的盲区；
 * 3. **主线程零阻塞**：bindService 回调在系统线程到达后投递到 worker；stub 跨进程调用在
 *    worker 上执行；结果/失败经 Handler 投回主线程回调调用方；
 * 4. 失败均映射为 [TRouterResult.Blocked]（不触发 onLost），并记录 [remote][fail] 日志。
 */
class RemoteRouter {

    private class Pending(
        val path: String,
        val bundle: Bundle?,
        val traceId: String,
        val log: (String) -> Unit,
        val onResult: (TRouterResult) -> Unit,
    )

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var workerRef: Handler? = null

    private fun worker(): Handler {
        workerRef?.let { return it }
        synchronized(this) {
            workerRef?.let { return it }
            val thread = HandlerThread("trouter-remote").apply { start() }
            return Handler(thread.looper).also { workerRef = it }
        }
    }

    // 以下字段仅允许在 worker 线程访问
    private val pending = ArrayDeque<Pending>()
    private var appContext: Context? = null
    private var component: ComponentName? = null
    private var service: IRouterService? = null
    private var connecting = false
    private var boundAttempt = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // 系统回调可能在任意线程到达：投递到 worker 串行处理
            worker().post { onConnected(binder) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            worker().post { onDisconnected() }
        }
    }

    fun navigate(
        context: Context,
        component: ComponentName?,
        whitelist: Set<String>?,
        path: String,
        bundle: Bundle?,
        onResult: (TRouterResult) -> Unit,
        logMessage: (String) -> Unit,
    ) {
        val traceId = UUID.randomUUID().toString().replace("-", "").take(8)

        // 同步守卫（不依赖通道状态，保证调用方第一时间得到明确结果）
        if (component == null) {
            logMessage("[remote][fail] traceId=$traceId path=$path reason=remote 服务未配置（TRouterConfig.remoteService）")
            onResult(TRouterResult.Blocked(path, "remote 服务未配置（TRouterConfig.remoteService）"))
            return
        }
        if (whitelist != null && path !in whitelist) {
            logMessage("[remote][fail] traceId=$traceId path=$path reason=path 未标注 @CrossProcess，禁止跨进程导航")
            onResult(TRouterResult.Blocked(path, "path 未标注 @CrossProcess，禁止跨进程导航"))
            return
        }

        val p = Pending(path, bundle, traceId, logMessage, onResult)
        worker().post { enqueue(context, component, p) }
    }

    // ---------------- worker 线程内执行 ----------------

    private fun enqueue(context: Context, component: ComponentName, p: Pending) {
        val app = context.applicationContext
        appContext = app
        this.component = component
        pending.addLast(p)
        p.log("[remote][send] traceId=${p.traceId} path=${p.path} targetProcess=${component.flattenToString()}")
        armTimeout(p)
        ensureBinding()
        dispatch()   // ① 入队后若已连接，立即派发
    }

    private fun ensureBinding() {
        if (service != null || connecting) return
        val ctx = appContext ?: return
        val comp = component ?: return
        connecting = true
        val ok = try {
            ctx.bindService(Intent().setComponent(comp), connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            false
        }
        boundAttempt = ok
        if (!ok) {
            connecting = false
            failAll("remote 服务不可用（bind 失败）")
        }
    }

    private fun dispatch() {
        if (service == null || pending.isEmpty()) return
        val batch = ArrayList(pending)
        pending.clear()
        for (p in batch) execute(p)
    }

    private fun onConnected(binder: IBinder?) {
        val stub = binder?.let { IRouterService.Stub.asInterface(it) }
        // D：AIDL 透明代理封装——后续调用统一经动态代理，为入参清洗/统计留单一扩展点
        service = stub?.let { RemoteProxies.delegating(it) }
        connecting = false
        dispatch()   // ② 连接建立后，派发等待中的请求
    }

    private fun onDisconnected() {
        // 服务中途断开：对仍等待的请求给出明确失败，未来 navigate 会重新 bind
        service = null
        failAll("remote 服务已断开")
    }

    private fun execute(p: Pending) {
        val stub = service
        if (stub == null) {
            // 极端竞态兜底：不应发生（dispatch 只在 service!=null 时清队），防御处理
            pending.addFirst(p)
            return
        }
        val startMs = SystemClock.elapsedRealtime()
        val raw = try {
            stub.navigate(p.path, p.bundle)
        } catch (t: Throwable) {
            null
        }
        val costMs = SystemClock.elapsedRealtime() - startMs
        val reply = raw?.let { RemoteReplyCodec.parse(it) }
        if (reply == null) {
            p.log("[remote][fail] traceId=${p.traceId} path=${p.path} reason=remote 调用异常/回包解析失败")
            postMain { p.onResult(TRouterResult.Blocked(p.path, "remote 调用异常")) }
            return
        }
        p.log(
            "[remote][recv] traceId=${reply.remoteTraceId} origin=${p.traceId} result=${RemoteReplyCodec.describe(reply)} costMs=$costMs",
        )
        postMain { p.onResult(RemoteReplyCodec.toLocalResult(reply)) }
    }

    private fun armTimeout(p: Pending) {
        worker().postDelayed({
            val removed = pending.remove(p)
            if (!removed) return@postDelayed
            p.log("[remote][fail] traceId=${p.traceId} path=${p.path} reason=remote 服务连接超时")
            postMain { p.onResult(TRouterResult.Blocked(p.path, "remote 服务连接超时")) }
            // 若还悬在“正在连接”且已无等待请求（如系统未回调 onServiceConnected），
            // 主动撤销这次 bind，避免 connecting 永久悬挂、后续请求无法重连
            if (connecting && service == null && pending.isEmpty() && boundAttempt) {
                try {
                    appContext?.unbindService(connection)
                } catch (t: Throwable) {
                    // ignore
                }
                connecting = false
                boundAttempt = false
            }
        }, CONNECT_TIMEOUT_MS)
    }

    private fun failAll(reason: String) {
        if (pending.isEmpty()) return
        val batch = ArrayList(pending)
        pending.clear()
        for (p in batch) {
            p.log("[remote][fail] traceId=${p.traceId} path=${p.path} reason=$reason")
            postMain { p.onResult(TRouterResult.Blocked(p.path, reason)) }
        }
    }

    private fun postMain(block: () -> Unit) {
        main.post { block() }
    }

    /** 测试底座 reset：断开连接并丢弃待处理请求（不回调）。 */
    fun disconnect() {
        worker().post {
            val ctx = appContext
            if (boundAttempt && ctx != null) {
                try {
                    ctx.unbindService(connection)
                } catch (t: Throwable) {
                    // ignore
                }
            }
            boundAttempt = false
            connecting = false
            service = null
            pending.clear()
            appContext = null
            component = null
        }
    }

    private companion object {
        /** bind 到 :remote 服务（含冷启动）的超时窗；高负载/慢设备可能超过 8s，取 15s。 */
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
