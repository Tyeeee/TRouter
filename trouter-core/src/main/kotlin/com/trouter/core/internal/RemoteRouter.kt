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
 * host 侧跨进程通道客户端（V4.0 + G2-remote）。
 *
 * 设计（防「顺序/时机依赖」缺陷）：
 * 1. 单后台线程（trouter-remote）串行执行全部操作：入队/连接回调/超时/派发；
 * 2. 派发由状态驱动：请求入队后与连接建立后都主动 dispatch；无“无人派发”终态；
 * 3. 主线程零阻塞（binder 调用在 worker 执行，回调投回主线程）；
 * 4. 导航失败映射 [TRouterResult.Blocked]；服务失败以 [RemoteReplyCodec.SERVICE_ERROR_PREFIX] 串表达。
 * 导航与服务共用一个连接与同一队列（统一 Task），保证顺序与重连语义一致。
 */
class RemoteRouter {

    private sealed class Task(val traceId: String, val log: (String) -> Unit) {
        class Nav(
            val path: String,
            val bundle: Bundle?,
            traceId: String,
            log: (String) -> Unit,
            val onResult: (TRouterResult) -> Unit,
        ) : Task(traceId, log)

        class Svc(
            val name: String,
            val args: Bundle?,
            traceId: String,
            log: (String) -> Unit,
            val onResult: (String) -> Unit,
        ) : Task(traceId, log)

        /** 批次 C：类型化调用（原生返回 Bundle，结果由调用方按生成物解包）。 */
        class Typed(
            val service: String,
            val method: String,
            val args: Bundle,
            traceId: String,
            log: (String) -> Unit,
            val onResult: (Bundle?) -> Unit,
        ) : Task(traceId, log)
    }

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
    private val queue = ArrayDeque<Task>()
    private var appContext: Context? = null
    private var component: ComponentName? = null
    private var service: IRouterService? = null
    private var connecting = false
    private var boundAttempt = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
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
        worker().post {
            enqueue(context, component, Task.Nav(path, bundle, traceId, logMessage, onResult))
        }
    }

    fun callService(
        context: Context,
        component: ComponentName?,
        name: String,
        args: Bundle?,
        onResult: (String) -> Unit,
        logMessage: (String) -> Unit,
    ) {
        val traceId = UUID.randomUUID().toString().replace("-", "").take(8)
        if (component == null) {
            logMessage("[remote][fail] traceId=$traceId name=$name reason=remote 服务未配置（TRouterConfig.remoteService）")
            onResult(RemoteReplyCodec.SERVICE_ERROR_PREFIX + "remote 服务未配置（TRouterConfig.remoteService）")
            return
        }
        worker().post {
            enqueue(context, component, Task.Svc(name, args, traceId, logMessage, onResult))
        }
    }

    /**
     * 批次 C：类型化跨进程调用（返回原生 Bundle）。
     * 失败统一回 null，由调用方（生成的客户端代理）转为错误回调。
     */
    fun callTyped(
        context: Context,
        component: ComponentName?,
        service: String,
        method: String,
        args: Bundle,
        onResult: (Bundle?) -> Unit,
        logMessage: (String) -> Unit,
    ) {
        val traceId = UUID.randomUUID().toString().replace("-", "").take(8)
        if (component == null) {
            logMessage("[remote][typed][fail] traceId=$traceId service=$service reason=remote 服务未配置")
            onResult(null)
            return
        }
        worker().post {
            enqueue(context, component, Task.Typed(service, method, args, traceId, logMessage, onResult))
        }
    }

    // ---------------- worker 线程内执行 ----------------

    private fun enqueue(context: Context, component: ComponentName, task: Task) {
        appContext = context.applicationContext
        this.component = component
        queue.addLast(task)
        when (task) {
            is Task.Nav ->
                task.log("[remote][send] traceId=${task.traceId} path=${task.path} targetProcess=${component.flattenToString()}")
            is Task.Svc ->
                task.log("[remote][service][send] traceId=${task.traceId} name=${task.name} targetProcess=${component.flattenToString()}")
            is Task.Typed ->
                task.log(
                    "[remote][typed][send] traceId=${task.traceId} service=${task.service} method=${task.method} " +
                        "targetProcess=${component.flattenToString()}",
                )
        }
        armTimeout(task)
        ensureBinding()
        dispatch() // ① 入队后若已连接，立即派发
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
            failAll(RemoteReplyCodec.SERVICE_ERROR_PREFIX + "remote 服务不可用（bind 失败）", navBlockedReason = "remote 服务不可用（bind 失败）")
        }
    }

    private fun dispatch() {
        if (service == null || queue.isEmpty()) return
        val batch = ArrayList(queue)
        queue.clear()
        for (task in batch) execute(task)
    }

    private fun onConnected(binder: IBinder?) {
        val stub = binder?.let { IRouterService.Stub.asInterface(it) }
        // D：AIDL 透明代理封装（导航与服务调用统一经代理）
        service = stub?.let { RemoteProxies.delegating(it) }
        connecting = false
        dispatch() // ② 连接建立后派发全部等待任务
    }

    private fun onDisconnected() {
        service = null
        failAll(RemoteReplyCodec.SERVICE_ERROR_PREFIX + "remote 服务已断开", navBlockedReason = "remote 服务已断开")
    }

    private fun execute(task: Task) {
        val stub = service
        if (stub == null) {
            queue.addFirst(task) // 防御：不应发生（dispatch 仅在 service!=null 时清队）
            return
        }
        when (task) {
            is Task.Nav -> executeNav(stub, task)
            is Task.Svc -> executeSvc(stub, task)
            is Task.Typed -> executeTyped(stub, task)
        }
    }

    private fun executeNav(stub: IRouterService, task: Task.Nav) {
        val startMs = SystemClock.elapsedRealtime()
        val raw = try {
            stub.navigate(task.path, task.bundle)
        } catch (t: Throwable) {
            null
        }
        val costMs = SystemClock.elapsedRealtime() - startMs
        val reply = raw?.let { RemoteReplyCodec.parse(it) }
        if (reply == null) {
            task.log("[remote][fail] traceId=${task.traceId} path=${task.path} reason=remote 调用异常/回包解析失败")
            postMain { task.onResult(TRouterResult.Blocked(task.path, "remote 调用异常")) }
            return
        }
        task.log(
            "[remote][recv] traceId=${reply.remoteTraceId} origin=${task.traceId} result=${RemoteReplyCodec.describe(reply)} costMs=$costMs" +
                if (reply.paramEcho.isNotEmpty()) " params=[${reply.paramEcho.joinToString("; ")}]" else "",
        )
        postMain { task.onResult(RemoteReplyCodec.toLocalResult(reply)) }
    }

    private fun executeSvc(stub: IRouterService, task: Task.Svc) {
        val startMs = SystemClock.elapsedRealtime()
        val raw = try {
            stub.callService(task.name, task.args)
        } catch (t: Throwable) {
            null
        }
        val costMs = SystemClock.elapsedRealtime() - startMs
        if (raw == null) {
            task.log("[remote][service][fail] traceId=${task.traceId} name=${task.name} reason=remote 调用异常")
            postMain { task.onResult(RemoteReplyCodec.SERVICE_ERROR_PREFIX + "remote 调用异常") }
            return
        }
        task.log("[remote][service][recv] traceId=${task.traceId} name=${task.name} costMs=$costMs reply=${if (raw.length > 120) raw.take(120) + "…" else raw}")
        postMain { task.onResult(raw) }
    }

    private fun executeTyped(stub: IRouterService, task: Task.Typed) {
        val startMs = SystemClock.elapsedRealtime()
        val raw = try {
            stub.callTyped(task.service, task.method, task.args)
        } catch (t: Throwable) {
            null
        }
        val costMs = SystemClock.elapsedRealtime() - startMs
        if (raw == null) {
            task.log("[remote][typed][fail] traceId=${task.traceId} service=${task.service} reason=remote 调用异常/回包为空")
        } else {
            task.log(
                "[remote][typed][recv] traceId=${task.traceId} service=${task.service} method=${task.method} " +
                    "costMs=$costMs keys=${raw.keySet()?.size ?: 0}",
            )
        }
        postMain { task.onResult(raw) }
    }

    private fun armTimeout(task: Task) {
        worker().postDelayed({
            val removed = queue.remove(task)
            if (!removed) return@postDelayed
            when (task) {
                is Task.Nav -> {
                    task.log("[remote][fail] traceId=${task.traceId} path=${task.path} reason=remote 服务连接超时")
                    postMain { task.onResult(TRouterResult.Blocked(task.path, "remote 服务连接超时")) }
                }
                is Task.Svc -> {
                    task.log("[remote][service][fail] traceId=${task.traceId} name=${task.name} reason=remote 服务连接超时")
                    postMain { task.onResult(RemoteReplyCodec.SERVICE_ERROR_PREFIX + "remote 服务连接超时") }
                }
                is Task.Typed -> {
                    task.log("[remote][typed][fail] traceId=${task.traceId} service=${task.service} reason=remote 服务连接超时")
                    postMain { task.onResult(null) }
                }
            }
            if (connecting && service == null && queue.isEmpty() && boundAttempt) {
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

    private fun failAll(errorSuffix: String, navBlockedReason: String) {
        if (queue.isEmpty()) return
        val batch = ArrayList(queue)
        queue.clear()
        for (task in batch) {
            when (task) {
                is Task.Nav -> {
                    task.log("[remote][fail] traceId=${task.traceId} path=${task.path} reason=$navBlockedReason")
                    postMain { task.onResult(TRouterResult.Blocked(task.path, navBlockedReason)) }
                }
                is Task.Svc -> {
                    task.log("[remote][service][fail] traceId=${task.traceId} name=${task.name} reason=$navBlockedReason")
                    postMain { task.onResult(errorSuffix) }
                }
                is Task.Typed -> {
                    task.log("[remote][typed][fail] traceId=${task.traceId} service=${task.service} reason=$navBlockedReason")
                    postMain { task.onResult(null) }
                }
            }
        }
    }

    private fun postMain(block: () -> Unit) {
        main.post { block() }
    }

    /** 测试底座 reset：断开连接并丢弃待处理任务（不回调）。 */
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
            queue.clear()
            appContext = null
            component = null
        }
    }

    private companion object {
        /** bind 到 :remote 服务（含冷启动）的超时窗；高负载/慢设备可能超过 8s，取 15s。 */
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
