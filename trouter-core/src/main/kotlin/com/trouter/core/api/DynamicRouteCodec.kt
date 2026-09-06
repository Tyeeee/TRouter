package com.trouter.core.api

/**
 * 动态路由序列化编解码（F，纯字符串、无 IO 依赖）。
 * 行格式：path\u0001group\u0001targetClassName\u0001kindName（字段不含控制符）。
 */
object DynamicRouteCodec {

    private const val SEP = "\u0001"

    fun encode(routes: List<RouteMeta>): String = routes.joinToString("\n") { meta ->
        listOf(meta.path, meta.group, meta.targetClassName, meta.kind.name).joinToString(SEP)
    }

    /** 容错解码：坏行跳过（返回可安全 apply 的合法集合，不抛异常）。 */
    fun decode(text: String): List<RouteMeta> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split(SEP)
            if (parts.size != 4) return@mapNotNull null
            try {
                RouteMeta(
                    path = parts[0],
                    group = parts[1],
                    targetClassName = parts[2],
                    kind = RouteTargetKind.valueOf(parts[3]),
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        .toList()
}
