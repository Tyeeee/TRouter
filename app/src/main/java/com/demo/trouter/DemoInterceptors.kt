package com.demo.trouter

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.trouter.core.api.AsyncChain
import com.trouter.core.api.AsyncInterceptor
import com.trouter.core.api.InterceptorDecision
import com.trouter.core.api.MockInterceptor
import com.trouter.core.api.RouteChainMember
import com.trouter.core.api.RouteInterceptor
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouterContract

/**
 * V2.0 演示拦截器集合（宿主装配 + 主页 S08/S09 开关共用的**有状态**实例）；
 * 批次 B 追加异步拦截器（S23–S25）。
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

    /** 异步拦截器（批次 B，S23–S25）：延时后放行，模拟网络风控/定位等真实异步检查。 */
    val async = AsyncDemoInterceptor()

    /** 供主页/测试装配的完整演示列表（默认全关闭/全放行）。 */
    val demoList: List<RouteChainMember> = listOf(gate, mock, async)
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

/**
 * 异步拦截器演示（批次 B）：模拟"调一次网络/定位再决定放不放行"。
 *
 * - [enabled]=false：立即放行（对同步 navigate 完全透明）；
 * - [enabled]=true：延时 [delayMs] 后放行；此时**同步 navigate 会被明确拒绝**
 *   （core 不允许阻塞主线程等待），必须改用 `TRouter.navigateAsync`（S24）；
 * - 把 [delayMs] 调到超过 `TRouterConfig.asyncInterceptorTimeoutMs`（demo 配 1500ms）即演示超时收口（S25）。
 */
class AsyncDemoInterceptor : AsyncInterceptor {

    var enabled: Boolean = false

    /** 模拟异步检查耗时（ms）。 */
    @Volatile
    var delayMs: Long = 300L

    private val handler = Handler(Looper.getMainLooper())

    override fun intercept(chain: AsyncChain) {
        val shouldWait = enabled && chain.meta.path == RouterContract.PATH_SECOND
        if (!shouldWait) {
            chain.proceed()
            return
        }
        handler.postDelayed({ chain.proceed { outcome -> } }, delayMs)
    }
}
