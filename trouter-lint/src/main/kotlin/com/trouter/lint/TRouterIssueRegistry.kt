package com.trouter.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/**
 * TRouter Lint 检查注册表（批次 A2）。
 *
 * 为什么需要它：KSP 只能看到 `@Route` 注解里的 path（已在处理器里做字面量分级），
 * 但**调用点**的硬编码（`TRouter.navigate("/second")`）它完全看不到——而调用点硬编码
 * 恰恰是"四个统一"里最普遍、也最难在评审中拦住的一类违规。
 * 本模块用 Android Lint 把调用点也能以 error 级别卡在 CI 上。
 */
class TRouterIssueRegistry : IssueRegistry() {

    override val issues: List<Issue> get() = listOf(RoutePathLiteralDetector.ISSUE)

    override val api: Int get() = CURRENT_API

    override val vendor: Vendor get() = Vendor(
        vendorName = "TRouter",
        identifier = "com.trouter",
        feedbackUrl = "https://github.com/Tyeeee/TRouter/issues",
    )
}
