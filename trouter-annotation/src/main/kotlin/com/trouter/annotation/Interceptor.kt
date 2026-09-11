package com.trouter.annotation

/**
 * 目标级拦截器标注（只给某个页面挂拦截器）：为某个 @Route 页面附加一组**按标识名**绑定的拦截器。
 *
 * - 必须与 @Route 同时标注（否则 KSP ERROR）；
 * - [names] 为运行时绑定标识：宿主/测试用 TRouter.bindTargetInterceptor(name, interceptor)
 *   将标识映射到实际拦截器实例（WrappingInterceptor 或 RouteInterceptor 均可）；
 * - 执行位置：全局拦截链**之后**、打开目标之前（与全局链同一不可变快照，单次 navigate 同步推进）。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Interceptor(val names: Array<String>)
