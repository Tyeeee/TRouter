package com.trouter.core.api

import android.os.Bundle

/**
 * 异步拦截器（批次 B）：允许在**回调里延后放行**，用于真实异步检查（网络风控、定位、读配置）。
 *
 * 为什么需要它：原来的链是同步调用栈，拦截器里一旦要等 IO，就只能阻塞主线程——
 * 这是"串行拦截器"真实存在的痛点（不是并发调度问题）。异步拦截器把"等待"变成合法的链行为：
 * 拦截器可以先返回，等自己的异步结果到了再调用 [AsyncChain.proceed] / [AsyncChain.block] /
 * [AsyncChain.redirect] 三者之一结束本轮。
 *
 * 语义与约束（由 TRouter 强制，见 TRouter.navigateAsync）：
 * - **单次终止**：`proceed` / `block` / `redirect` 三选一，只能调用一次；重复调用直接抛
 *   [IllegalStateException]（防"放行两次 = 开两次页"）；
 * - **超时兜底**：超过 `TRouterConfig.asyncInterceptorTimeoutMs` 未终止 → 按 Blocked 收口
 *   （reason 含超时信息），此后再调用 proceed 一律被忽略；
 * - **取消**：调用方通过 `RouteRequest.cancel()` 取消后，迟到的 proceed 不会打开目标；
 * - **执行线程**：异步拦截器可以在任意线程结束本轮，剩余链与打开目标由框架**切回主线程**执行；
 * - **结果回调**：`onResult` 恒在主线程，且**只回调一次**（超时/取消/正常三选一）。
 *
 * 与同步 `TRouter.navigate` 的关系（两条规则）：
 * - 若异步拦截器**立即放行**（例如开关关着就直接 proceed，不等待 IO）→ 同步导航照常可用，不受影响；
 * - 若它**延迟放行**（真正需要等待）→ 同步导航返回 `Blocked`（reason 提示改用 `navigateAsync`），
 *   绝不阻塞主线程等待。宁可报错，也不制造主线程卡顿。
 */
interface AsyncInterceptor : RouteChainMember {
    fun intercept(chain: AsyncChain)
}

/**
 * 异步链句柄：交给 [AsyncInterceptor] 用来结束本轮（三选一，单次有效）。
 *
 * 典型用法：
 * ```
 * override fun intercept(chain: AsyncChain) {
 *     http.check(chain.bundle) { passed ->
 *         if (passed) chain.proceed { outcome -> /* 后置观察（不能改写结果） */ }
 *         else chain.block("风控未通过")
 *     }
 * }
 * ```
 */
interface AsyncChain {

    /** 本次导航的目标元数据（path/group/kind/目标类名）。 */
    val meta: RouteMeta

    /** 本次导航的调用方参数（只读语义：请勿修改）。 */
    val bundle: Bundle?

    /** 本次导航的 traceId（与日志同一来源）。 */
    val traceId: String

    /** 是否已被调用方取消（取消后 proceed 不会打开目标）。 */
    val isCancelled: Boolean

    /**
     * 放行剩余链（执行后续拦截器并最终打开目标）。
     * [done] 会在剩余链结束后被调用，供本拦截器做**后置观察**（例如耗时统计）；
     * 注意后置**不能改写**结果——需要改写请在放行前决定。
     */
    fun proceed(done: (ChainOutcome) -> Unit = {})

    /** 终止本次导航（返回 [TRouterResult.Blocked]，不打开目标、不触发 onLost）。 */
    fun block(reason: String)

    /** 改道到另一条已注册 path（与同步拦截器的 Redirect 同语义，同 traceId 重入）。 */
    fun redirect(targetPath: String)
}
