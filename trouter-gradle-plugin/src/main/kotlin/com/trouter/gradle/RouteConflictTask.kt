package com.trouter.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * 跨模块路由 path 冲突校验（构建期闸门）。
 *
 * 为什么需要它：KSP 只能看到「单个模块」的注解，跨模块重复 path 它发现不了；
 * 而 path 冲突正是组件化里最常见、也最难查的事故（两个 feature 抢同一个路径 → 运行时谁先注册谁生效）。
 * 本任务把各模块 KSP 生成的路由清单汇总后统一查重，冲突即构建失败。
 */
abstract class RouteConflictTask : DefaultTask() {

    /** 各模块的 KSP 生成目录（默认 `<module>/build/generated/ksp/<variant>/kotlin`）。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedDirs: ConfigurableFileCollection

    /** "模块路径|生成目录" 条目，仅用于校验与报错定位（与 generatedDirs 同源）。 */
    @get:Input
    abstract val moduleEntries: ListProperty<String>

    @TaskAction
    fun verify() {
        data class Found(val path: String, val module: String, val file: String)

        val found = ArrayList<Found>()
        var scannedModules = 0
        for (entry in moduleEntries.get()) {
            val module = entry.substringBefore('|')
            val dirPath = entry.substringAfter('|')
            val dir = File(dirPath)
            if (!dir.exists()) {
                logger.lifecycle("TRouter 跨模块校验 [skip] 模块 $module 生成目录不存在：$dirPath")
                continue
            }
            scannedModules++
            dir.walkTopDown()
                .filter { it.isFile && it.name.startsWith("GroupLoader_") && it.extension == "kt" }
                .forEach { file ->
                    PATH_REGEX.findAll(file.readText()).forEach { m ->
                        found.add(Found(m.groupValues[1], module, file.name))
                    }
                }
        }

        val dupes = found.groupBy { it.path }
            .filter { (_, list) -> list.map { it.module }.toSet().size > 1 }
        if (dupes.isNotEmpty()) {
            val detail = dupes.map { (path, list) ->
                val byModule = list.groupBy { it.module }
                    .map { (m, items) -> "$m(${items.joinToString(",") { it.file }})" }
                "$path ← ${byModule.joinToString(" / ")}"
            }
            throw GradleException(
                "TRouter 跨模块路由 path 冲突：${detail.joinToString("；")}。" +
                    "请把 path 统一到 RouterContract 常量后修正（冲突时运行时按先注册者生效，务必消除）。" +
                    "提示：若刚删除过页面/路由，请先执行 --rerun-tasks 或 clean 排除生成物过期导致的误报。",
            )
        }
        logger.lifecycle(
            "TRouter 跨模块校验通过：无重复 path（扫描模块 $scannedModules 个，路由清单项 ${found.size} 条）",
        )
    }

    private companion object {
        val PATH_REGEX = Regex("""path\s*=\s*"([^"]+)"""")
    }
}
