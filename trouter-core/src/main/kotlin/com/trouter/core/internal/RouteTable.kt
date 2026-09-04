package com.trouter.core.internal

import com.trouter.core.api.RouteMeta

/**
 * 路由表：path → RouteMeta。
 * 路由冲突已在 KSP 阶段拦截；此处 register 幂等（重复注册跳过并计数），
 * 避免重复 install 抛异常，同时保留可观测性。
 */
class RouteTable {

    private val table = LinkedHashMap<String, RouteMeta>()

    /** @return true 表示新增，false 表示该 path 已存在（跳过）。 */
    fun register(meta: RouteMeta): Boolean {
        if (table.containsKey(meta.path)) return false
        table[meta.path] = meta
        return true
    }

    fun find(path: String): RouteMeta? = table[path]

    /** @return true 表示确有移除（V5.0 动态注销/静态注销共用）。 */
    fun remove(path: String): Boolean {
        if (!table.containsKey(path)) return false
        table.remove(path)
        return true
    }

    /** 只读快照（外部展示/观测用），返回新列表。 */
    fun snapshot(): List<RouteMeta> = ArrayList(table.values)

    fun clear() = table.clear()

    fun size(): Int = table.size
}
