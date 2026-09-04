package com.trouter.core.api

import android.os.Bundle

/**
 * 拦截器决策（V2.0）：拦截链中单个拦截器的求值结果，禁止 null。
 * - [Continue]：放行，继续下一个拦截器（或最终打开目标）；
 * - [Block]：终止本次导航（返回 [TRouterResult.Blocked]，不触发 onLost、不打开目标）；
 * - [Redirect]：把本次导航改写为另一条已注册 path（重新走完整导航，跳数上限见 TRouter）。
 */
sealed class InterceptorDecision {
    data object Continue : InterceptorDecision()
    data class Block(val reason: String) : InterceptorDecision()
    data class Redirect(val targetPath: String) : InterceptorDecision()
}

/**
 * 路由拦截器（V2.0）：唯一抽象，宿主经 [TRouterConfig.interceptors] 注入，
 * 在「路由解析后、目标打开前」按列表顺序执行（见 TRouter.navigate）。
 */
fun interface RouteInterceptor {
    fun intercept(meta: RouteMeta, bundle: Bundle?): InterceptorDecision
}

/**
 * core 内置 Mock 拦截器：把 [redirects] 映射命中的 path 改写为对应 mock path
 * （典型场景：isDebug 演示期把真实页替换为 Mock 页，生产宿主不注入即可）。
 *
 * [enabled] 是行为开关（非日志开关）：关闭时一律放行；不受 isDebug 影响。
 */
class MockInterceptor(
    private val redirects: Map<String, String>,
) : RouteInterceptor {

    var enabled: Boolean = false

    override fun intercept(meta: RouteMeta, bundle: Bundle?): InterceptorDecision {
        if (!enabled) return InterceptorDecision.Continue
        val target = redirects[meta.path] ?: return InterceptorDecision.Continue
        return InterceptorDecision.Redirect(target)
    }
}
