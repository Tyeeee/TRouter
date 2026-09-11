package com.demo.trouter

import com.trouter.annotation.RemoteApi

/**
 * 多进程与跨进程增强 演示：**类型化远程接口**（跨进程调用不再手写 Bundle 与字符串协议）。
 *
 * 约定：最后一个参数是回调；其余参数与回调结果类型走统一白名单
 * （String/Int/…、枚举、List、@RemotePojo）。客户端拿到的是动态代理，
 * 远端实现由 `TRouterRemoteApi_DemoStatsApi.register(...)` 在 :remote2 进程登记。
 */
@RemoteApi
interface DemoStatsApi {

    /** 演示基础类型参数 + 基础类型结果。 */
    fun count(q: String, onResult: (Int) -> Unit)

    /** 演示"结果是一个 @RemotePojo 业务对象"。 */
    fun report(id: String, onResult: (DemoReport) -> Unit)

    /** 演示多参数 + 枚举参数 + List<Int> 参数 + String 结果。 */
    fun summarize(tag: String, level: DemoLevel, values: List<Int>, onResult: (String) -> Unit)
}
