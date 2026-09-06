package com.trouter.core.api

import android.os.Bundle

/** 拦截器注册成员标记：config.interceptors 里只允许 [RouteInterceptor]（V2 原子）与 [WrappingInterceptor]（L4 洋葱）。 */
interface RouteChainMember {
    /**
     * 全局拦截优先级（G11，与 ARouter 对齐）。
     * 越大越先执行；默认 0 = 保持注册/声明顺序（稳定排序）。
     * 语义：仅在同批全局链内按优先级排序，目标级链仍按 @Interceptor(names) 顺序排在全局后。
     */
    val priority: Int
        get() = 0
}

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
 * 路由拦截器（V2.0）：原子形态（无包裹能力），宿主经 [TRouterConfig.interceptors] 注入，
 * 在「路由解析后、目标打开前」按列表顺序执行（见 TRouter.navigate）。
 */
fun interface RouteInterceptor : RouteChainMember {
    fun intercept(meta: RouteMeta, bundle: Bundle?): InterceptorDecision
}

/**
 * 洋葱链结果（L4）：整条链（含最终打开目标）的最终收口。
 * - [Opened]：目标已打开（含"全部放行"与包裹后置通过的情况）；
 * - [Blocked]：被某拦截器终止（携带 path/reason）；
 * - [Redirected]：被改写为另一条 path（由 TRouter 以同 traceId 重新导航，跳数上限见 TRouter）。
 */
sealed class ChainOutcome {
    data class Opened(val meta: RouteMeta) : ChainOutcome()
    data class Blocked(val path: String, val reason: String) : ChainOutcome()
    data class Redirected(val targetPath: String) : ChainOutcome()
}

/**
 * 洋葱链（L4）：暴露给 [WrappingInterceptor] 的执行句柄。
 * [proceed] 同步执行**剩余拦截器并最终打开目标**：包裹拦截器可在 proceed() 之前/之后做前置/后置逻辑。
 */
interface InterceptorChain {
    val meta: RouteMeta
    val bundle: Bundle?

    /**
     * 放行到剩余链：执行后续拦截器直至打开目标，返回链结果。
     * 同一 chain 实例只允许调用一次；重复调用抛 [IllegalStateException]（防止"开两次页"类缺陷）。
     */
    fun proceed(): ChainOutcome
}

/**
 * 洋葱包裹拦截器（L4）：通过 [InterceptorChain.proceed] 包裹后续逻辑。
 *
 * 典型洋葱形态：
 * ```
 * class AuditInterceptor : WrappingInterceptor {
 *     override fun intercept(chain: InterceptorChain): ChainOutcome {
 *         // 前置
 *         val outcome = chain.proceed()   // 放行：后续拦截器 → 打开目标
 *         // 后置（outcome is Opened 等）
 *         return outcome
 *     }
 * }
 * ```
 * 也可不调用 proceed() 直接返回 [ChainOutcome.Blocked]/[ChainOutcome.Redirected]（拦截语义）。
 */
interface WrappingInterceptor : RouteChainMember {
    fun intercept(chain: InterceptorChain): ChainOutcome
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
