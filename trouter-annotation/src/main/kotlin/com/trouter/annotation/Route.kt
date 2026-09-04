package com.trouter.annotation

/**
 * 路由声明注解。
 *
 * 约束（由 trouter-processor 在 KSP 阶段强制/告警）：
 * - [path] 必须引用 RouterContract 常量（`com.trouter.core.api.RouterContract`），
 *   直接书写字符串字面量会输出 Warning；
 * - 同一 path 不能出现在多个类上（编译错误）；
 * - 目标类必须是 Activity 或 Fragment 的子类（编译错误）。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Route(
    val path: String,
    val group: String = Route.DEFAULT_GROUP,
) {
    companion object {
        const val DEFAULT_GROUP: String = "default"
    }
}
