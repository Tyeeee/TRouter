package com.trouter.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * TRouter 跨模块路由冲突校验插件（id: `com.trouter.route-conflict`）。
 *
 * 消费方式（宿主/app 模块一行接入）：
 * ```
 * plugins { id("com.trouter.route-conflict") }
 * ```
 * 之后：
 * - `./gradlew :app:verifyTRouterRoutes` 手动跑；
 * - `check` / `assemble*` 会自动带上（可用 trouterConflict.autoWire = false 关掉）。
 *
 * 校验依赖各模块的 KSP 生成物，因此本任务会自行 dependsOn 各模块的 ksp<Variant>Kotlin。
 */
class RouteConflictPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val ext = target.extensions.create("trouterConflict", RouteConflictExtension::class.java)
        ext.variants.convention(listOf("debug"))
        ext.autoWire.convention(true)
        ext.modules.convention(emptyMap())

        val verify = target.tasks.register("verifyTRouterRoutes", RouteConflictTask::class.java) { task ->
            task.group = "verification"
            task.description = "TRouter 跨模块路由 path 冲突校验（构建期闸门，插件 com.trouter.route-conflict 提供）"
        }

        // 工程结构就绪后再发现各模块的 KSP 任务与生成目录
        target.gradle.projectsEvaluated {
            val entries = LinkedHashSet<String>()
            val dirs = target.files()
            val explicit = ext.modules.get()

            if (explicit.isNotEmpty()) {
                explicit.forEach { (module, dir) ->
                    val resolved = target.file(dir)
                    entries.add("$module|${resolved.absolutePath}")
                    dirs.from(resolved)
                }
            } else {
                target.rootProject.allprojects.forEach { sub ->
                    ext.variants.get().forEach { variant ->
                        val kspTaskName = "ksp${variant.replaceFirstChar { it.uppercase() }}Kotlin"
                        val kspTask = sub.tasks.findByName(kspTaskName) ?: return@forEach
                        verify.configure { it.dependsOn(kspTask) }
                        val dir = sub.layout.buildDirectory.dir("generated/ksp/$variant/kotlin")
                        entries.add("${sub.path}|${dir.get().asFile.absolutePath}")
                        dirs.from(dir)
                    }
                }
            }

            verify.configure {
                it.moduleEntries.set(entries.toList())
                it.generatedDirs.from(dirs)
            }

            if (ext.autoWire.get()) {
                target.tasks.matching { it.name == "check" || it.name.startsWith("assemble") }
                    .configureEach { it.dependsOn(verify) }
            }
        }
    }
}
