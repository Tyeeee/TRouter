package com.trouter.core.api

import android.os.Bundle

/**
 * POJO 跨进程编解码器契约（多进程与跨进程增强）。
 *
 * 由 KSP 为每个 `@RemotePojo` 类生成实现（零反射），生成物形如：
 * ```
 * object TRouterPojo_DemoReport : TRouterPojoCodec {
 *     override val className = "com.demo.trouter.DemoReport"
 *     override val prefix = "pojo.DemoReport."
 *     fun pack(bundle: Bundle, value: DemoReport)
 *     fun unpack(bundle: Bundle): DemoReport
 * }
 * ```
 * 该接口让**框架侧**（例如后续的类型化远程服务代理）可以在不知道具体类型的情况下
 * 按类名取到编解码器，统一完成参数与返回值的打包/解包。
 */
interface TRouterPojoCodec {

    /** 该编解码器负责的类全名。 */
    val className: String

    /** 字段默认前缀（生成时固定，避免同一个 Bundle 里多个 POJO 字段互相覆盖）。 */
    val prefix: String

    /** 把 [value] 打包进 [bundle]（调用方保证类型匹配；不匹配会抛 ClassCastException）。 */
    fun pack(bundle: Bundle, value: Any)

    /** 从 [bundle] 还原对象。 */
    fun unpack(bundle: Bundle): Any
}
