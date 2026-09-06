package com.trouter.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSValueArgument
import java.io.File

/**
 * TRouter KSP 处理器。
 *
 * 职责（V1.0）：
 * - 扫描 @Route，按 group 生成 GroupLoader_<Group> 与聚合 TRouterGroupRegistry；
 * - 编译期校验（Fail-Fast）：
 *   C1 同一 path 多类声明 → logger.error 中断编译；
 *   C2 path 字面量 → Warning（提示引用 RouterContract）；
 *   类型非法（非 Activity/Fragment 子类）→ error；
 *   R-2 降级：常量引用无法求值时 Warning + __UNRESOLVED_CONSTANT__ 占位 + 跳过该路由生成。
 */
class TRouterProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {

    private val codeGenerator: CodeGenerator = env.codeGenerator
    private val logger: KSPLogger = env.logger

    private val modulePackage: String =
        env.options["trouter.modulePackage"]?.takeIf { it.isNotBlank() } ?: run {
            logger.error("KSP 参数缺失：请在宿主模块配置 ksp { arg(\"trouter.modulePackage\", \"<module.namespace>\") }")
            ""
        }

    /** group -> 该组路由 */
    private val routesByGroup = LinkedHashMap<String, MutableList<ParsedRoute>>()
    private val seenClasses = LinkedHashSet<String>()
    private var emitted = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (modulePackage.isEmpty() || emitted) return emptyList()
        val symbols = resolver.getSymbolsWithAnnotation(ANNOTATION_ROUTE).toList()
        for (symbol in symbols) {
            val decl = symbol as? KSClassDeclaration
            if (decl == null) {
                logger.error("@Route 只能标注在类上", symbol)
                continue
            }
            val fqcn = decl.qualifiedName?.asString() ?: continue
            if (!seenClasses.add(fqcn)) continue
            parseRoute(decl)?.let { parsed ->
                routesByGroup.getOrPut(parsed.group) { mutableListOf() }.add(parsed)
            }
        }
        // @CrossProcess 校验：必须同时标注 @Route（白名单路径即来自这些路由）
        for (symbol in resolver.getSymbolsWithAnnotation(ANNOTATION_CROSS)) {
            val decl = symbol as? KSClassDeclaration
            if (decl == null) {
                logger.error("@CrossProcess 只能标注在类上", symbol)
                continue
            }
            if (decl.annotations.none { it.shortName.asString() == ROUTE_SHORT_NAME }) {
                logger.error(
                    "@CrossProcess 目标必须同时标注 @Route：${decl.qualifiedName?.asString()}（白名单基于 @Route 的 path 生成）",
                    decl,
                )
            }
        }
        // @Interceptor 校验：必须同时标注 @Route（目标级拦截器依附于路由目标）
        for (symbol in resolver.getSymbolsWithAnnotation(ANNOTATION_TARGET_INTERCEPTOR)) {
            val decl = symbol as? KSClassDeclaration
            if (decl == null) {
                logger.error("@Interceptor 只能标注在类上", symbol)
                continue
            }
            if (decl.annotations.none { it.shortName.asString() == ROUTE_SHORT_NAME }) {
                logger.error(
                    "@Interceptor 目标必须同时标注 @Route：${decl.qualifiedName?.asString()}",
                    decl,
                )
            }
        }
        // 不在本轮生成：全部收集完成后在 finish() 统一校验与生成
        return emptyList()
    }

    override fun finish() {
        if (modulePackage.isEmpty() || emitted) return
        emitted = true

        // C1：路径冲突（同 path 多类声明）→ 编译错误；C2：字面量 → Warning
        val pathOwner = HashMap<String, String>()
        for ((_, list) in routesByGroup) {
            for (route in list) {
                if (route.pathEval.literal) {
                    logger.warn(
                        "@Route path 使用了字符串字面量（${route.pathEval.value}），" +
                            "请引用 RouterContract 常量，禁止硬编码字符串。",
                        route.decl,
                    )
                }
                val eval = route.pathEval
                if (!eval.resolved) continue
                val previous = pathOwner.putIfAbsent(eval.value, route.className)
                if (previous != null) {
                    logger.error(
                        "路由路径冲突（Fail-Fast）：path=${eval.value} 已被 [$previous] 注册，" +
                            "[${route.className}] 重复声明，请修正后重新编译。",
                        route.decl,
                    )
                }
            }
        }

        // 生成（含 R-2：unresolved 的条目被跳过，不进入任何 GroupLoader）
        if (routesByGroup.isEmpty()) return

        val allSources: Array<KSFile> = routesByGroup.values.flatten()
            .mapNotNull { it.decl.containingFile }
            .distinct()
            .toTypedArray()
        val loaderClassNames = mutableListOf<String>()
        val usedSanitized = HashMap<String, Int>()

        for ((group, list) in routesByGroup) {
            val resolvable = list.filter { it.pathEval.resolved }
            if (resolvable.isEmpty()) continue
            val baseSan = sanitize(group)
            val san = if (usedSanitized.containsKey(baseSan)) {
                val n = usedSanitized.getValue(baseSan) + 1
                usedSanitized[baseSan] = n
                "$baseSan${'$'}$n"
            } else {
                usedSanitized[baseSan] = 0
                baseSan
            }
            val loaderClass = "GroupLoader_$san"
            loaderClassNames.add(loaderClass)
            emitGroupLoader(group, san, resolvable, allSources)
        }

        if (loaderClassNames.isNotEmpty()) {
            emitRegistry(loaderClassNames, allSources)
        }

        // @CrossProcess 白名单（V4.0）：从带 CrossProcess 注解的路由收集，生成 CrossProcessPaths
        val crossRoutes = routesByGroup.values.flatten()
            .filter { route ->
                route.pathEval.resolved &&
                    route.decl.annotations.any { it.shortName.asString() == CROSS_SHORT_NAME }
            }
        if (crossRoutes.isNotEmpty()) {
            val crossSources: Array<KSFile> = crossRoutes
                .mapNotNull { it.decl.containingFile }
                .distinct()
                .toTypedArray()
            emitCrossProcessPaths(crossRoutes.map { it.pathEval.value }.distinct(), crossSources)
        }

        // @Interceptor 目标级映射（L3）：className -> 拦截器标识名列表（无标注模块也生成空表，保证宿主可引用）
        val interceptorRoutes = routesByGroup.values.flatten()
            .filter { route -> route.decl.annotations.any { it.shortName.asString() == INTERCEPTOR_SHORT_NAME } }
        val targetMap = LinkedHashMap<String, List<String>>()
        for (route in interceptorRoutes) {
            val names = extractInterceptorNames(route.decl)
            if (names.isNotEmpty()) targetMap[route.className] = names
        }
        val interceptorSources: Array<KSFile> = interceptorRoutes
            .mapNotNull { it.decl.containingFile }
            .distinct()
            .toTypedArray()
        emitTargetInterceptorNames(targetMap, interceptorSources)
    }

    /** 从 @Interceptor 注解读取 names（非空去重字符串列表；异常时返回空）。 */
    private fun extractInterceptorNames(decl: KSClassDeclaration): List<String> {
        val annotation = decl.annotations.firstOrNull { it.shortName.asString() == INTERCEPTOR_SHORT_NAME }
            ?: return emptyList()
        val raw = annotation.arguments.firstOrNull { it.name?.asString() == "names" }?.value
        return when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }.distinct()
            else -> emptyList()
        }
    }

    private fun emitTargetInterceptorNames(targetMap: Map<String, List<String>>, sources: Array<KSFile>) {
        val branches = if (targetMap.isEmpty()) {
            "        else -> emptyList()"
        } else {
            targetMap.entries.joinToString("\n") { (cls, names) ->
                "        ${q(cls)} -> listOf(${names.joinToString(", ") { q(it) }})"
            } + "\n        else -> emptyList()"
        }
        val content = """
            |// 本文件由 TRouter KSP 处理器生成，请勿手改。
            |package $modulePackage.generated
            |
            |/**
            | * 目标级拦截器映射（L3）：targetClassName -> @Interceptor names。
            | * 宿主把本表与 TRouter.bindTargetInterceptor(name, ...) 配合使用。
            | */
            |object TRouterTargetInterceptorNames {
            |    fun namesOf(targetClassName: String): List<String> = when (targetClassName) {
            |$branches
            |    }
            |}
            |
        """.trimMargin()
        val deps = Dependencies(aggregating = true, *sources)
        codeGenerator.createNewFile(deps, "$modulePackage.generated", "TRouterTargetInterceptorNames").use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    // ------------------------------------------------------------------ 解析

    private fun parseRoute(decl: KSClassDeclaration): ParsedRoute? {
        val annotation = decl.annotations.firstOrNull { it.shortName.asString() == ROUTE_SHORT_NAME }
            ?: return null

        val pathArg = annotation.arguments.firstOrNull { it.name?.asString() == "path" }
        val groupArg = annotation.arguments.firstOrNull { it.name?.asString() == "group" }
        val group = (groupArg?.value as? String)?.takeIf { it.isNotBlank() }
            ?: RouteDefaultGroup

        val pathEval = evaluatePath(decl, pathArg)

        val kind = resolveKind(decl)
        if (kind == null) {
            logger.error(
                "@Route 目标类必须是 Activity 或 Fragment 的子类：${decl.qualifiedName?.asString()}",
                decl,
            )
            return null
        }

        return ParsedRoute(
            decl = decl,
            className = decl.qualifiedName!!.asString(),
            group = group,
            kind = kind,
            pathEval = pathEval,
        )
    }

    /**
     * path 参数求值。
     *
     * 实证结论（Step 2 探针）：KSP2 会把「引用二进制依赖 const val」的注解参数折叠为字面值，
     * 因此 raw is String 即代表可解析；字面量与否由源文件判定（决定是否 C2 Warning）。
     * value 为 null 的异常路径按 R-2 可落地规则处理（Warning + __UNRESOLVED_CONSTANT__ + 跳过生成）。
     */
    private fun evaluatePath(decl: KSClassDeclaration, pathArg: KSValueArgument?): PathEval {
        if (pathArg == null) {
            // annotation 的 path 无默认值，正常情况下必然存在；防御性处理
            return PathEval(value = UNRESOLVED_CONSTANT, resolved = false, literal = false)
        }
        val raw = pathArg.value
        if (raw is String) {
            val literal = isLiteralInSource(decl, pathArg)
            return PathEval(value = raw, resolved = true, literal = literal)
        }
        // R-2：常量引用无法求值 → 占位并跳过该路由生成
        logger.warn(
            "@Route path 引用的常量无法解析（RouterContract 常量无法解析，请检查依赖配置）。" +
                "该条路由将记为 __UNRESOLVED_CONSTANT__ 并跳过生成。",
            decl,
        )
        return PathEval(value = UNRESOLVED_CONSTANT, resolved = false, literal = false)
    }

    /** 判定 path 参数在源码中是否为字符串字面量（决定 C2 Warning）。 */
    private fun isLiteralInSource(decl: KSClassDeclaration, pathArg: KSValueArgument): Boolean {
        val region = annotationRegionText(decl) ?: return false
        return pathArg.name?.asString() == "path" &&
            LITERAL_PATH_REGEX.containsMatchIn(region)
    }

    /**
     * 从源文件读取该类的 @Route 注解块文本（括号平衡扫描），
     * 用于字面量判定。读取失败返回 null（此时不做 C2 Warning，宁缺勿误报）。
     */
    private fun annotationRegionText(decl: KSClassDeclaration): String? {
        val file = decl.containingFile ?: return null
        val path = file.filePath
        if (!path.endsWith(".kt")) return null
        val text = try {
            File(path).readText()
        } catch (e: Exception) {
            return null
        }
        val simpleName = decl.simpleName.asString()
        val lines = text.split("\n")

        // 定位 "class <SimpleName>"（不含 @Route 的行），再向上找最近的 @Route
        var classLine = -1
        for ((index, line) in lines.withIndex()) {
            if (line.contains("class $simpleName") && !line.contains("@Route")) {
                classLine = index
                break
            }
        }
        if (classLine < 0) return null

        var annotationLine = classLine
        while (annotationLine > 0 && annotationLine > classLine - 10) {
            if (lines[annotationLine].contains("@Route")) break
            annotationLine--
        }
        if (annotationLine < 0 || !lines[annotationLine].contains("@Route")) return null

        // 括号平衡扫描（支持 @Route 跨多行）
        val sb = StringBuilder()
        var depth = 0
        var started = false
        var lineIdx = annotationLine
        while (lineIdx < lines.size) {
            val line = lines[lineIdx]
            val from = if (lineIdx == annotationLine) line.indexOf("@Route") else 0
            if (from >= 0) {
                var i = from
                while (i < line.length) {
                    val ch = line[i]
                    if (ch == '(') {
                        started = true
                        depth++
                    } else if (ch == ')' && started) {
                        depth--
                    }
                    if (started) sb.append(ch)
                    if (started && depth == 0) return sb.toString()
                    i++
                }
            }
            if (started) {
                sb.append('\n')
                lineIdx++
            } else {
                lineIdx++
            }
        }
        return null
    }

    /** 沿父类型链判定 ACTIVITY / FRAGMENT；两者皆非返回 null（交由调用方报错）。 */
    private fun resolveKind(decl: KSClassDeclaration): TargetKind? {
        var current: KSClassDeclaration? = decl
        var depth = 0
        while (current != null && depth < 32) {
            val name = current.qualifiedName?.asString()
            when (name) {
                "android.app.Activity",
                "androidx.activity.ComponentActivity",
                "androidx.fragment.app.FragmentActivity",
                -> return TargetKind.ACTIVITY

                "androidx.fragment.app.Fragment",
                -> return TargetKind.FRAGMENT
            }
            val parent = current.superTypes.firstOrNull()?.resolve()?.declaration as? KSClassDeclaration
            current = parent
            depth++
        }
        return null
    }

    // ------------------------------------------------------------------ 生成

    private fun emitGroupLoader(
        group: String,
        san: String,
        routes: List<ParsedRoute>,
        sources: Array<KSFile>,
    ) {
        val className = "GroupLoader_$san"
        val metas = routes.joinToString(",\n") { route ->
            "        RouteMeta(\n" +
                "            path = ${q(route.pathEval.value)},\n" +
                "            group = ${q(route.group)},\n" +
                "            targetClassName = ${q(route.className)},\n" +
                "            kind = RouteTargetKind.${route.kind},\n" +
                "        )"
        }
        val content = """
            |// 本文件由 TRouter KSP 处理器生成，请勿手改。
            |package $modulePackage.generated
            |
            |import com.trouter.core.api.GroupLoader
            |import com.trouter.core.api.RouteMeta
            |import com.trouter.core.api.RouteTargetKind
            |
            |class $className : GroupLoader {
            |    override val group: String = ${q(group)}
            |
            |    override fun routeMetas(): List<RouteMeta> = listOf(
            |$metas
            |    )
            |}
            |
        """.trimMargin()
        // 聚合输出（registry 汇总全部 loader、loader 汇总组内全部路由）必须用 aggregating=true：
        // 任一 @Route 源文件变化都触发全量重扫，避免 KSP 增量把生成物覆盖成「只含本轮变更符号」
        val deps = Dependencies(aggregating = true, *sources)
        codeGenerator.createNewFile(deps, "$modulePackage.generated", className).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    private fun emitRegistry(loaderClassNames: List<String>, sources: Array<KSFile>) {
        val listBody = loaderClassNames.joinToString(",\n") { "        $it()" }
        val content = """
            |// 本文件由 TRouter KSP 处理器生成，请勿手改。
            |package $modulePackage.generated
            |
            |import com.trouter.core.api.GroupLoader
            |import com.trouter.core.api.GroupLoaderRegistry
            |
            |object TRouterGroupRegistry : GroupLoaderRegistry {
            |    override fun loaders(): List<GroupLoader> = listOf(
            |$listBody
            |    )
            |}
            |
        """.trimMargin()
        val deps = Dependencies(aggregating = true, *sources)
        codeGenerator.createNewFile(deps, "$modulePackage.generated", "TRouterGroupRegistry").use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    private fun emitCrossProcessPaths(paths: List<String>, sources: Array<KSFile>) {
        val content = """
            |// 本文件由 TRouter KSP 处理器生成，请勿手改。
            |package $modulePackage.generated
            |
            |/**
            | * @CrossProcess 白名单：允许经跨进程通道导航的 path 集合（V4.0）。
            | * 宿主配置 TRouterConfig.remoteWhitelist 引用本集合。
            | */
            |object CrossProcessPaths {
            |    val paths: Set<String> = setOf(
            |${paths.joinToString(",\n") { "        ${q(it)}" }}
            |    )
            |}
            |
        """.trimMargin()
        val deps = Dependencies(aggregating = true, *sources)
        codeGenerator.createNewFile(deps, "$modulePackage.generated", "CrossProcessPaths").use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    private fun sanitize(group: String): String {
        val base = group.map { c ->
            if (c.isLetterOrDigit() || c == '_') c else '_'
        }.joinToString("")
        return when {
            base.isEmpty() -> "default"
            base.first().isDigit() -> "G$base"
            else -> base
        }
    }

    private fun q(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""

    // ------------------------------------------------------------------ 模型

    private data class ParsedRoute(
        val decl: KSClassDeclaration,
        val className: String,
        val group: String,
        val kind: TargetKind,
        val pathEval: PathEval,
    )

    private enum class TargetKind { ACTIVITY, FRAGMENT }

    private data class PathEval(
        val value: String,
        val resolved: Boolean,
        val literal: Boolean,
    )

    companion object {
        const val ANNOTATION_ROUTE: String = "com.trouter.annotation.Route"
        const val ROUTE_SHORT_NAME: String = "Route"
        const val ANNOTATION_CROSS: String = "com.trouter.annotation.CrossProcess"
        const val CROSS_SHORT_NAME: String = "CrossProcess"
        const val ANNOTATION_TARGET_INTERCEPTOR: String = "com.trouter.annotation.Interceptor"
        const val INTERCEPTOR_SHORT_NAME: String = "Interceptor"
        const val RouteDefaultGroup: String = "default"
        const val UNRESOLVED_CONSTANT: String = "__UNRESOLVED_CONSTANT__"
        val LITERAL_PATH_REGEX = Regex("""path\s*=\s*"[^"]*"""")
    }
}
