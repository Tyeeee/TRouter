package com.trouter.core.api

/**
 * 路由表 JSON 编解码（G6）。
 *
 * 规范格式（导出/导入同构，字段顺序固定）：顶层数组，每项
 * `{"path":..,"group":..,"targetClassName":..,"kind":"ACTIVITY|FRAGMENT"}`。
 * 无第三方 JSON 依赖，采用自包含的迷你实现；导入要求符合规范格式，否则返回 null（不落任何变更）。
 */
object RouteMapCodec {

    fun toJson(metas: List<RouteMeta>): String = metas.joinToString(",", "[", "]") { meta ->
        buildString {
            append('{')
            appendField("path", meta.path).append(',')
            appendField("group", meta.group).append(',')
            appendField("targetClassName", meta.targetClassName).append(',')
            append("\"kind\":").append('"').append(meta.kind.name).append('"')
            append('}')
        }
    }

    private fun StringBuilder.appendField(key: String, value: String): StringBuilder =
        append('"').append(key).append("\":").append('"').append(escape(value)).append('"')

    private fun escape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
    }

    private fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val next = value[i + 1]
                when (next) {
                    '"' -> append('"')
                    '\\' -> append('\\')
                    'n' -> append('\n')
                    else -> { append(c); append(next) }
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /** 解析规范数组；不符合格式（含字段缺失/kind 非法/JSON 结构错乱）返回 null。 */
    fun fromJson(text: String): List<RouteMeta>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        val body = trimmed.substring(1, trimmed.length - 1).trim()
        if (body.isEmpty()) return emptyList()
        val result = ArrayList<RouteMeta>()
        // 简单对象切分：逐对象提取（字段值已转义，不含裸 }
        var index = 0
        while (index < body.length) {
            val objStart = body.indexOf('{', index)
            if (objStart < 0) return null
            val objEnd = body.indexOf('}', objStart)
            if (objEnd < 0) return null
            val block = body.substring(objStart, objEnd)
            val meta = parseObject(block) ?: return null
            result.add(meta)
            index = objEnd + 1
            // 期望逗号或结束
            val rest = body.substring(index).trimStart()
            if (rest.isEmpty()) break
            if (!rest.startsWith(",")) return null
            index = body.length - rest.length + 1
        }
        return result
    }

    private fun field(block: String, key: String): String? {
        val marker = "\"$key\"\\s*:\\s*\""
        val regex = Regex("""$marker(.*?)""", RegexOption.DOT_MATCHES_ALL)
        val m = regex.find(block) ?: return null
        var i = m.range.last + 1
        val sb = StringBuilder()
        while (i < block.length) {
            val c = block[i]
            if (c == '\\' && i + 1 < block.length) {
                sb.append(c).append(block[i + 1]); i += 2; continue
            }
            if (c == '"') break
            sb.append(c)
            i++
        }
        if (i >= block.length || block[i] != '"') return null
        return unescape(sb.toString())
    }

    private fun parseObject(block: String): RouteMeta? {
        val path = field(block, "path")?.takeIf { it.isNotBlank() } ?: return null
        val group = field(block, "group") ?: return null
        val target = field(block, "targetClassName")?.takeIf { it.isNotBlank() } ?: return null
        val kind = field(block, "kind") ?: return null
        val kindEnum = runCatching { RouteTargetKind.valueOf(kind) }.getOrNull() ?: return null
        return RouteMeta(path = path, group = group, targetClassName = target, kind = kindEnum)
    }
}
