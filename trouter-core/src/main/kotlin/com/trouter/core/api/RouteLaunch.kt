package com.trouter.core.api

import android.content.Intent
import android.os.Bundle

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

    /** Intent 便捷入口（委托给 extras）。 */
    fun describe(intent: Intent?): String = describe(intent?.extras)

    /**
     * 统一「本次由 TRouter 打开」运行时证据文案（页面去重用，避免每个页面复制一段拼接逻辑）。
     * @param src Activity intent extras 或 Fragment arguments；无路由元数据时返回直连提示。
     */
    fun describe(src: Bundle?): String {
        val path = src?.getString(EXTRA_PATH) ?: return DIRECT_LAUNCH_HINT
        val kind = src.getString(EXTRA_KIND)
        val group = src.getString(EXTRA_GROUP)
        val traceId = src.getString(EXTRA_TRACE_ID)
        val costMs = src.getLong(EXTRA_COST_MS, -1L)
        val costPart = if (costMs >= 0) " · 解析 ${costMs}ms" else ""
        return "✓ 本次由 TRouter 打开 · path=$path · kind=$kind · group=$group · traceId=$traceId$costPart"
    }

    /** 直连/系统启动（未带路由元数据）时的固定提示。 */
    const val DIRECT_LAUNCH_HINT: String = "（直连/系统启动：本页未带 TRouter 路由元数据）"
}

