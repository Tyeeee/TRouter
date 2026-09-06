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
)
