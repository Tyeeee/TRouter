package com.trouter.core.api

/**
 * 分组加载器：一个 group 对应一个生成实现（GroupLoader_<Group>），
 * 负责以元数据（不含任何页面类引用）的形式声明该组路由。
 */
interface GroupLoader {
    val group: String
    fun routeMetas(): List<RouteMeta>
}

/**
 * 分组注册表：KSP 为每个使用模块生成唯一实现（TRouterGroupRegistry），
 * 供 TRouter.install(...) 装配路由。
 */
interface GroupLoaderRegistry {
    fun loaders(): List<GroupLoader>
}
