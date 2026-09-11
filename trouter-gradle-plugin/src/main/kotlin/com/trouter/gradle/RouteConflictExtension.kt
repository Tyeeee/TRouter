package com.trouter.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * 跨模块路由冲突校验插件配置（可选，全部有默认值）。
 *
 * 默认行为：自动发现本工程中所有跑过 KSP 的子工程，扫描其
 * `build/generated/ksp/<variant>/kotlin` 下的 GroupLoader 生成文件，
 * 同一 path 出现在 ≥2 个模块即构建失败；并把校验挂到 check / assemble* 上。
 */
abstract class RouteConflictExtension {

    /** 参与校验的构建变体（默认 debug）。 */
    abstract val variants: ListProperty<String>

    /** 是否自动挂到 check 与 assemble*（默认 true）。关掉后只能显式跑 verifyTRouterRoutes。 */
    abstract val autoWire: Property<Boolean>

    /**
     * 手动指定「模块名 = 生成目录」覆盖自动发现（默认空）。
     * 仅在工程结构特殊（生成目录不在默认位置）时才需要。
     */
    abstract val modules: MapProperty<String, String>
}
