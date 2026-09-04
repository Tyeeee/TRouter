package com.trouter.core.api

/**
 * 统一导航结果：所有导航方法返回本密封类型，禁止返回 null，
 * 强制调用方处理失败分支（NotFound / NotInitialized）。
 */
sealed class TRouterResult {
    data class Success(val meta: RouteMeta) : TRouterResult()

    data class NotFound(val path: String) : TRouterResult()

    /** 未调用 TRouter.init 就发起导航。 */
    data object NotInitialized : TRouterResult()

    /**
     * V2.0：导航被拦截器终止（Block / 拦截器故障 / Redirect 超跳数）。
     * 与 NotFound 语义隔离：不触发 onLost、不打开目标。
     */
    data class Blocked(val path: String, val reason: String) : TRouterResult()
}
