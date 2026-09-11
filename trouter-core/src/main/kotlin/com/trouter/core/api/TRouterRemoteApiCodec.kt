package com.trouter.core.api

import android.os.Bundle

/**
 * 单个方法的编解码器（多进程与跨进程增强，由 KSP 为 `@RemoteApi` 接口的每个方法生成）。
 *
 * - [encode]：把调用方实参（按声明顺序，**不含最后的回调**）写进 args Bundle；
 * - [decode]：从远端回包 Bundle 里取出结果值（类型与接口声明一致）。
 */
interface TRouterRemoteMethodCodec {
    fun encode(args: Bundle, values: List<Any?>)
    fun decode(reply: Bundle): Any?
}

/**
 * 类型化远程接口的编解码契约（多进程与跨进程增强）。
 *
 * 生成物形如：
 * ```
 * object TRouterRemoteApi_DemoStatsApi : TRouterRemoteApiCodec {
 *     override val apiName = "com.demo.DemoStatsApi"
 *     override val apiClass: Class<*> = DemoStatsApi::class.java
 *     override val methods = mapOf("count" to object : TRouterRemoteMethodCodec { ... })
 *     fun registerClient(): Boolean        // 客户端进程调用一次
 *     fun register(impl: DemoStatsApi)     // 远端进程调用，登记实现
 * }
 * ```
 * 客户端通过 [TRouter.remoteApi] 拿到动态代理；代理把方法调用编码后经 AIDL 类型化通道发到远端，
 * 结果解码后回到主线程触发接口声明的回调。
 */
interface TRouterRemoteApiCodec {

    /** 远端注册名（默认是接口全名，可用 @RemoteApi(name = ...) 覆盖）。 */
    val apiName: String

    /** 接口类型（客户端按类型查找编解码器）。 */
    val apiClass: Class<*>

    /** 方法名 → 方法编解码器。 */
    val methods: Map<String, TRouterRemoteMethodCodec>
}

/** 类型化通道的回包约定：成功标记 + 失败原因（都在同一个 Bundle 里，避免字符串二次编码）。 */
object TRouterTypedReply {

    const val KEY_OK: String = "__trouter_ok"
    const val KEY_ERROR: String = "__trouter_error"

    fun ok(reply: Bundle): Boolean = reply.getBoolean(KEY_OK, false)

    fun errorOf(reply: Bundle?): String = reply?.getString(KEY_ERROR) ?: "远端未返回结果（可能超时或进程已退出）"

    fun success(): Bundle = Bundle().apply { putBoolean(KEY_OK, true) }

    fun failure(reason: String): Bundle = Bundle().apply {
        putBoolean(KEY_OK, false)
        putString(KEY_ERROR, reason)
    }
}
