package com.trouter.core.api

/**
 * 路由启动元数据（Intent extra / Fragment arguments 键）。
 *
 * TRouter.openTarget 在打开目标时写入本页路由元数据（path/group/kind/traceId/解析耗时），
 * 目标页可据此渲染「本次由 TRouter 成功打开」的运行时证据（可观测性、测试台自述）；
 * 无这些键 = 页面被直连/系统启动（未走路由），页面应据此明示。
 */
object RouteLaunch {
    const val EXTRA_PATH: String = "com.trouter.core.extra.PATH"
    const val EXTRA_GROUP: String = "com.trouter.core.extra.GROUP"
    const val EXTRA_KIND: String = "com.trouter.core.extra.KIND"
    const val EXTRA_TRACE_ID: String = "com.trouter.core.extra.TRACE_ID"
    const val EXTRA_COST_MS: String = "com.trouter.core.extra.COST_MS"
}
