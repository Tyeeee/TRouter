package com.trouter.core.api

/** 路由目标类型（由 KSP 依据类型层级判定并写入生成代码）。 */
enum class RouteTargetKind {
    ACTIVITY,
    FRAGMENT,
}

/**
 * 路由元数据 —— 稳定核心数据结构（开闭原则：定义后不随版本变动字段）。
 *
 * 只保存目标类**名字符串**，绝不持有 Class 引用：
 * init/install 阶段因此不会加载任何页面类，真正加载发生在 navigate 时。
 */
data class RouteMeta(
    val path: String,
    val group: String,
    val targetClassName: String,
    val kind: RouteTargetKind,
)
