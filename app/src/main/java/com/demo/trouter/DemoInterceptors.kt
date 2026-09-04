package com.demo.trouter

import android.os.Bundle
import com.trouter.core.api.InterceptorDecision
import com.trouter.core.api.MockInterceptor
import com.trouter.core.api.RouteInterceptor
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouterContract

/**
 * V2.0 演示拦截器集合（宿主装配 + 主页 S08/S09 开关共用的**有状态**实例）。
 *
 * 统一治理：拦截器仍经 TRouterConfig.interceptors 注入（见 TRouterDemoApp），
 * 开关只是翻转实例自身状态（行为开关，非日志开关，不受 isDebug 影响）。
 */
object DemoInterceptors {

    /** 门禁拦截器（Block 演示，S08）：开启后拦截 /second，navigate 返回 Blocked。 */
    val gate = GateInterceptor()

    /** Mock 拦截器（Redirect 演示，S09）：开启后 /second → /mock/second 重定向。 */
    val mock = MockInterceptor(
        redirects = mapOf(RouterContract.PATH_SECOND to RouterContract.PATH_MOCK_SECOND),
    )

    /** 供主页/测试装配的完整演示列表（默认全关闭 = 全放行）。 */
    val demoList: List<RouteInterceptor> = listOf(gate, mock)
}

/** 门禁拦截器：业务形态示例（Block 决策），放 :app 演示层、不进 core。 */
class GateInterceptor : RouteInterceptor {

    /** 行为开关：开启后拦截 /second，其余路径一律放行。 */
    var enabled: Boolean = false

    override fun intercept(meta: RouteMeta, bundle: Bundle?): InterceptorDecision {
        if (!enabled) return InterceptorDecision.Continue
        if (meta.path == RouterContract.PATH_SECOND) {
            return InterceptorDecision.Block("演示门禁开启：拦截 ${RouterContract.PATH_SECOND}")
        }
        return InterceptorDecision.Continue
    }
}
