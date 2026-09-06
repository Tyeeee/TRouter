package com.trouter.processor

import java.io.File

/**
 * 跨模块路由 path 冲突扫描（L1，构建期）。
 *
 * 用法（由 Gradle 任务以 JavaExec 调用）：
 *   args = [moduleA, generatedDirA, moduleB, generatedDirB, ...]
 * 逐个模块扫描其 KSP 生成的 GroupLoader_*.kt 中的 path 常量；
 * 同一 path 出现在 ≥2 个不同模块 → 打印冲突明细并以退出码 1 结束（使构建失败）；
 * 无冲突 → 打印统计并以 0 结束。
 */
object CrossModuleConflictScanner {

    private val PATH_REGEX = Regex("""path\s*=\s*"([^"]+)"""")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size % 2 == 0) { "用法：args = [moduleName, generatedDir, ...]" }
        val modules = ArrayList<Pair<String, File>>(args.size / 2)
        var i = 0
        while (i + 1 < args.size) {
            modules.add(args[i] to File(args[i + 1]))
            i += 2
        }

        data class Found(val path: String, val module: String, val file: String)
        val all = ArrayList<Found>()
        for ((moduleName, dir) in modules) {
            if (!dir.exists()) {
                println("TRouter L1 [debug] module=$moduleName dir 不存在: ${dir.absolutePath}")
                continue
            }
            dir.walkTopDown()
                .filter { it.isFile && it.name.startsWith("GroupLoader_") && it.extension == "kt" }
                .forEach { file ->
                    PATH_REGEX.findAll(file.readText()).forEach { m ->
                        all.add(Found(m.groupValues[1], moduleName, file.absolutePath))
                    }
                }
        }

        val dupes = all.groupBy { it.path }
            .filter { (_, list) -> list.map { it.module }.toSet().size > 1 }
        if (dupes.isNotEmpty()) {
            val detail = dupes.map { (path, list) ->
                val groups = list.groupBy { it.module }.map { (m, items) ->
                    "$m(${items.joinToString(",") { it.file.substringAfterLast('/') }})"
                }
                "$path ← ${groups.joinToString(" / ")}"
            }
            System.err.println("TRouter L1 跨模块路由 path 冲突：${detail.joinToString("；")}。请统一到 RouterContract 后修正。")
            kotlin.system.exitProcess(1)
        }
        println("TRouter L1 校验通过：跨模块无重复 path（共扫描 ${all.size} 条路由清单项）")
    }
}
