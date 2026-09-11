package com.trouter.core.api

import android.content.ComponentName

/**
 * 统一配置：所有可调参数集中注入，禁止散落全局变量。
 *
 * V1.0 字段：
 * - [isDebug]：true 时日志完整输出；false 时日志静默（R3 验收点）；
 * - [logSink]：日志输出通道，默认 null 表示走 android.util.Log；测试注入收集器断言；
 * - [onLost]：未找到路径时的全局降级回调（navigate 返回 NotFound 后触发），不受 isDebug 影响。
 *
 * V2.0 新增：
 * - [interceptors]：拦截器列表（顺序即执行顺序，见 RouteInterceptor）；默认空列表 = 行为与 V1.0 一致。
 *   不设独立 enableMockInterceptor 布尔：Mock 能力由 core MockInterceptor + 本列表统一承载。
 *
 * V4.0 新增（跨进程通道）：
 * - [remoteService]：remote 进程 AIDL 服务组件；null = 未启用跨进程导航（navigateRemote 返回 Blocked）；
 * - [remoteWhitelist]：@CrossProcess 白名单（KSP 生成 CrossProcessPaths.paths）；null = 不校验（全部放行），
 *   非 null = 只允许集合内 path 走跨进程通道。
 *
 * L3 新增（目标级拦截器）：
 * - [targetInterceptorResolver]：由宿主注入「目标类 → @Interceptor 标识名」解析函数
 *   （通常引用 KSP 生成的 TRouterTargetInterceptorNames.namesOf）；null = 无目标级拦截。
 *
 * 差距收敛新增：
 * - [deeplinkSchemes]：允许经 navigateUri 进入路由的 scheme 白名单（G1）；空集合 = 未启用深链。
 *
 * 批次 C 新增（多进程）：
 * - [remoteServices]：**额外的**跨进程服务组件，key 为逻辑目标名（如 "remote2"）。
 *   `navigateRemote/callRemoteService` 的 `target` 参数即按此表查找；`target=null` 时走 [remoteService]（默认进程）。
 *   宿主可为每个额外进程声明一个 `RemoteRouterService` 子类（该类是 open）并在 manifest 指定 android:process。
 *
 * 批次 B 新增（异步拦截器）：
 * - [asyncInterceptorTimeoutMs]：异步拦截器单轮终止的超时时间（默认 5000ms）。
 *   超过该时间未调用 proceed/block/redirect → 该次导航按 Blocked 收口（reason 含超时信息），
 *   此后再调用 proceed 一律被忽略。同步 navigate 不受本项影响：它只接受**立即放行**的异步成员，
 *   遇到延迟放行的成员会直接 Blocked 并提示改用 navigateAsync。
 */
open class TRouterConfig(
    val isDebug: Boolean = false,
    val logSink: ((String) -> Unit)? = null,
    val onLost: ((path: String) -> Unit)? = null,
    val interceptors: List<RouteChainMember> = emptyList(),
    val remoteService: ComponentName? = null,
    val remoteWhitelist: Set<String>? = null,
    val targetInterceptorResolver: ((targetClassName: String) -> List<String>)? = null,
    val deeplinkSchemes: Set<String> = emptySet(),
    val asyncInterceptorTimeoutMs: Long = 5_000L,
    val remoteServices: Map<String, ComponentName> = emptyMap(),
)
