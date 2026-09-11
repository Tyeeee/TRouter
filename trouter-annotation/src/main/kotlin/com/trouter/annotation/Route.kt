package com.trouter.annotation

/**
 * 路由声明注解。
 *
 * 约束（由 trouter-processor 在 KSP 阶段强制/告警）：
 * - [path] 必须引用 RouterContract 常量（`com.trouter.core.api.RouterContract`），
 *   直接书写字符串字面量会告警或报错，级别由 KSP 参数 `trouter.pathSeverity` 决定：
 *   `warning`（默认，向后兼容）/ `error`（新工程与 CI 建议，编译期直接卡住）；
 * - 同一 path 不能出现在多个类上（编译错误）；
 * - 目标类必须是 Activity 或 Fragment 的子类（编译错误）。
 *
 * [allowLiteral] 是字面量的**免责通道**，只用于两个合法场景：
 * ① 子模块自建契约常量、确实引用不到宿主 RouterContract；② 临时验证路径。
 * 置 true 仅豁免**本条注解**的告警/报错，不改变任何运行时行为。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Route(
    val path: String,
    val group: String = Route.DEFAULT_GROUP,
    val allowLiteral: Boolean = false,
) {
    companion object {
        const val DEFAULT_GROUP: String = "default"
    }
}
