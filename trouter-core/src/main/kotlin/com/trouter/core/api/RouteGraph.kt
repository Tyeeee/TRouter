package com.trouter.core.api

/**
 * 路由图谱（运行时注册路径版本）：由当前路由表（静态 + 动态）实时导出的只读模型。
 *
 * - [nodes]：目标类全名（去重、按首次出现顺序）；节点完全由边推导，无孤立节点；
 * - [edges]：每条注册路由一条边（含动态注册路由）。
 *
 * 与 [TRouter.registeredRoutes] 的差异：registeredRoutes 面向路由清单，
 * 图谱面向「类 ↔ path」的图视角（demo 文本展示 / 测试断言用）。
 */
data class RouteGraph(
    val nodes: List<String>,
    val edges: List<RouteGraphEdge>,
) {
    data class RouteGraphEdge(
        val path: String,
        val group: String,
        val kind: RouteTargetKind,
        val toClass: String,
    )
}
