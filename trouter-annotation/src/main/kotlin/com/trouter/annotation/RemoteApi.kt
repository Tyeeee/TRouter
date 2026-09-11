package com.trouter.annotation

/**
 * 类型化远程接口标注（多进程与跨进程增强）：标在**接口**上，处理器为该接口生成
 * `TRouterRemoteApi_<接口名>`（实现 `TRouterRemoteApiCodec`）。
 *
 * 接口方法形态（处理器强制，违反即编译错误）：
 * ```
 * @RemoteApi
 * interface DemoStatsApi {
 *     fun count(q: String, onResult: (Int) -> Unit)          // 参数 + 最后一个回调
 *     fun report(id: String, onResult: (DemoReport) -> Unit)  // 返回值也可以是 @RemotePojo
 * }
 * ```
 * - 方法**必须有返回值类型为 Unit、且类型为 `(T) -> Unit` 的最后一个参数**（客户端异步拿结果）；
 * - 其余参数与 `T` 支持：String / Int / Long / Float / Double / Boolean、枚举、
 *   `List<String|Int|Long>`、以及 `@RemotePojo` 数据类（自动复用其编解码器）；
 * - 远端实现按同签名实现接口，并在远端进程注册：
 *   `TRouterRemoteApi_DemoStatsApi.register(impl)`；
 * - 客户端进程注册编解码器（一行）：`TRouterRemoteApi_DemoStatsApi.registerClient()`，
 *   之后即可 `TRouter.remoteApi(DemoStatsApi::class.java, "remote2")` 拿到动态代理调用。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RemoteApi(val name: String = "")
