package com.trouter.core.testing

import android.content.ComponentName
import com.trouter.core.api.RouteChainMember
import com.trouter.core.api.TRouterConfig

/**
 * 测试统一配置：默认 isDebug=true（R3 日志完整输出分支），
 * 并接入日志收集器与 onLost 记录器，便于行为断言。
 * V2.0 起可注入 [interceptors]（拦截器用例：S08/S09/S10）。
 */
class TestConfig(
    isDebug: Boolean = true,
    logSink: ((String) -> Unit)? = null,
    onLost: ((path: String) -> Unit)? = null,
    interceptors: List<RouteChainMember> = emptyList(),
    remoteService: ComponentName? = null,
    remoteWhitelist: Set<String>? = null,
    targetInterceptorResolver: ((targetClassName: String) -> List<String>)? = null,
) : TRouterConfig(
    isDebug = isDebug,
    logSink = logSink,
    onLost = onLost,
    interceptors = interceptors,
    remoteService = remoteService,
    remoteWhitelist = remoteWhitelist,
    targetInterceptorResolver = targetInterceptorResolver,
)
