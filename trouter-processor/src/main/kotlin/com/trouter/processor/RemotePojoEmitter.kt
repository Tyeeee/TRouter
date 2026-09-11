package com.trouter.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Variance

/**
 * `@RemotePojo` 编解码器生成（批次 C）。
 *
 * 为每个标注类生成 `TRouterPojo_<SimpleName>`（实现 [com.trouter.core.api.TRouterPojoCodec]）：
 * - `pack(bundle, prefix, value)` / `unpack(bundle, prefix)`：带前缀版本，供**嵌套字段**复用；
 * - `pack(bundle, value)` / `unpack(bundle)`：默认前缀的便捷版本；
 * - 可空字段用 `<key>.__null` 标志位表达 null（Bundle 无法区分"没写"与"写了 null"）。
 *
 * 支持类型白名单（不在白名单直接编译报错，不做静默降级）：
 * String / Int / Long / Float / Double / Boolean、枚举（存 name）、
 * `List<String>` / `List<Int>` / `List<Long>`、以及**同模块**内另一个 @RemotePojo 类。
 */
internal class RemotePojoEmitter(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val modulePackage: String,
) {

    private data class Field(
        val name: String,
        val kind: Kind,
        val nullable: Boolean,
        val nestedSimpleName: String? = null,
        val enumTypeName: String? = null,
        val enumShortName: String? = null,
    )

    private data class PojoModel(
        val decl: KSClassDeclaration,
        val className: String,
        val simpleName: String,
        val fields: List<Field>,
    )

    private enum class Kind { STRING, INT, LONG, FLOAT, DOUBLE, BOOLEAN, ENUM, STRING_LIST, INT_LIST, LONG_LIST, POJO }

    private val emitted = LinkedHashSet<String>()

    fun emit(allAnnotated: List<KSClassDeclaration>) {
        val models = ArrayList<PojoModel>()
        for (decl in allAnnotated) {
            val fqcn = decl.qualifiedName?.asString() ?: continue
            if (!emitted.add(fqcn)) continue
            parse(decl, fqcn)?.let { models.add(it) }
        }
        if (models.isEmpty()) return
        val sources: Array<KSFile> = models.mapNotNull { it.decl.containingFile }.distinct().toTypedArray()
        models.forEach { emitOne(it, sources) }
        emitRegistry(models, sources)
    }

    // ------------------------------------------------------------------ 解析

    private fun parse(decl: KSClassDeclaration, fqcn: String): PojoModel? {
        val ctor = decl.primaryConstructor
        if (ctor == null) {
            logger.error("@RemotePojo 目标必须有主构造函数（推荐 data class）：$fqcn", decl)
            return null
        }
        val propertyNames = decl.getAllProperties().map { it.simpleName.asString() }.toSet()
        val fields = ArrayList<Field>()
        var ok = true
        for (param in ctor.parameters) {
            val name = param.name?.asString() ?: continue
            if (name !in propertyNames) {
                logger.error(
                    "@RemotePojo 主构造函数参数必须同时是属性（建议写成 data class）：$fqcn 的参数 [$name] 不是属性",
                    decl,
                )
                ok = false
                continue
            }
            val field = classify(param.type.resolve(), name, fqcn, decl)
            if (field == null) ok = false else fields.add(field)
        }
        if (!ok) return null
        return PojoModel(decl, fqcn, decl.simpleName.asString(), fields)
    }

    private fun classify(type: KSType, fieldName: String, owner: String, decl: KSClassDeclaration): Field? {
        val nullable = type.isMarkedNullable
        val desc = type.declaration.qualifiedName?.asString() ?: type.declaration.simpleName.asString()

        fun fail(): Field? {
            logger.error(
                "@RemotePojo 字段类型不受支持：$owner.$fieldName 的类型是 $desc。" +
                    "支持：String/Int/Long/Float/Double/Boolean（可空亦可）、枚举、" +
                    "List<String>/List<Int>/List<Long>、同模块内另一个 @RemotePojo 类；" +
                    "其他类型请先在业务侧转换为白名单类型。",
                decl,
            )
            return null
        }

        // List<T>：只支持三种元素类型，且要求声明为 List（MutableList/ArrayList 不支持）
        if (desc == "kotlin.collections.List" || desc == "kotlin.collections.MutableList" || desc.startsWith("kotlin.collections.List")) {
            if (desc != "kotlin.collections.List") return fail()
            val arg = type.arguments.singleOrNull() ?: return fail()
            if (arg.variance == Variance.STAR) return fail()
            val argType = arg.type?.resolve() ?: return fail()
            return when (argType.declaration.qualifiedName?.asString()) {
                "kotlin.String" -> Field(fieldName, Kind.STRING_LIST, false)
                "kotlin.Int" -> Field(fieldName, Kind.INT_LIST, false)
                "kotlin.Long" -> Field(fieldName, Kind.LONG_LIST, false)
                else -> fail()
            }
        }

        return when (desc) {
            "kotlin.String" -> Field(fieldName, Kind.STRING, nullable)
            "kotlin.Int" -> Field(fieldName, Kind.INT, nullable)
            "kotlin.Long" -> Field(fieldName, Kind.LONG, nullable)
            "kotlin.Float" -> Field(fieldName, Kind.FLOAT, nullable)
            "kotlin.Double" -> Field(fieldName, Kind.DOUBLE, nullable)
            "kotlin.Boolean" -> Field(fieldName, Kind.BOOLEAN, nullable)
            else -> {
                val target = type.declaration as? KSClassDeclaration ?: return fail()
                when {
                    target.classKind == ClassKind.ENUM_CLASS ->
                        Field(
                            fieldName,
                            Kind.ENUM,
                            nullable,
                            enumTypeName = desc,
                            enumShortName = target.simpleName.asString(),
                        )
                    target.annotations.any { it.shortName.asString() == REMOTE_POJO_SHORT } -> {
                        // v1 明确要求同模块：跨模块嵌套会引用不到对方的生成物
                        if (target.packageName.asString() != modulePackage) {
                            logger.error(
                                "@RemotePojo 嵌套字段必须与本类在同一模块：$owner.$fieldName → $desc" +
                                    "（跨模块嵌套会引用不到对方的编解码器；请把 POJO 收敛到同一模块）",
                                decl,
                            )
                            null
                        } else {
                            Field(fieldName, Kind.POJO, nullable, nestedSimpleName = target.simpleName.asString())
                        }
                    }
                    else -> fail()
                }
            }
        }
    }

    // ------------------------------------------------------------------ 生成

    private fun emitOne(model: PojoModel, sources: Array<KSFile>) {
        val packBody = StringBuilder()
        val unpackArgs = StringBuilder()

        for (f in model.fields) {
            val key = "prefix + \"${f.name}\""
            val nullKey = "prefix + \"${f.name}.__null\""

            val packStatement = packStatement(f, key)
            if (f.nullable) {
                packBody.appendLine("        if (value.${f.name} == null) {")
                packBody.appendLine("            bundle.putBoolean($nullKey, true)")
                packBody.appendLine("        } else {")
                packBody.appendLine("            bundle.putBoolean($nullKey, false)")
                packBody.appendLine("            $packStatement")
                packBody.appendLine("        }")
            } else {
                packBody.appendLine("        $packStatement")
            }

            val valueExpr = unpackExpr(f, key)
            val finalExpr = if (f.nullable) {
                "if (bundle.getBoolean($nullKey, true)) null else $valueExpr"
            } else {
                valueExpr
            }
            unpackArgs.appendLine("            ${f.name} = $finalExpr,")
        }

        val content = """
            |// 本文件由 TRouter KSP 处理器生成，请勿手改。
            |package $modulePackage.generated
            |
            |import android.os.Bundle
            |import com.trouter.core.api.TRouterPojoCodec
            |import ${model.className}
            |${enumImports(model)}
            |/**
            | * @RemotePojo 生成物：${model.className} ↔ Bundle（零反射，不要求 Parcelable）。
            | * 可空字段用 `<key>.__null` 标记；嵌套 POJO 复用对方生成物的带前缀方法。
            | */
            |object TRouterPojo_${model.simpleName} : TRouterPojoCodec {
            |
            |    override val className: String = "${model.className}"
            |
            |    override val prefix: String = "pojo.${model.simpleName}."
            |
            |    fun pack(bundle: Bundle, prefix: String, value: ${model.simpleName}) {
            |${packBody.toString().trimEnd()}
            |    }
            |
            |    fun pack(bundle: Bundle, value: ${model.simpleName}) = pack(bundle, prefix, value)
            |
            |    fun unpack(bundle: Bundle, prefix: String): ${model.simpleName} = ${model.simpleName}(
            |${unpackArgs.toString().trimEnd()}
            |    )
            |
            |    /** 默认前缀还原（同时实现 TRouterPojoCodec 的接口方法，返回类型协变）。 */
            |    override fun unpack(bundle: Bundle): ${model.simpleName} = unpack(bundle, prefix)
            |
            |    override fun pack(bundle: Bundle, value: Any) = pack(bundle, value as ${model.simpleName})
            |}
            |
        """.trimMargin()

        val deps = Dependencies(aggregating = true, *sources)
        codeGenerator.createNewFile(deps, "$modulePackage.generated", "TRouterPojo_${model.simpleName}").use {
            it.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    /** 枚举字段需要显式 import（生成文件里只 import 了 POJO 自身）。 */
    private fun enumImports(model: PojoModel): String =
        model.fields.filter { it.kind == Kind.ENUM }
            .mapNotNull { it.enumTypeName }
            .distinct()
            .joinToString("\n") { "import $it" }

    private fun packStatement(f: Field, key: String): String {
        val value = "value.${f.name}"
        return when (f.kind) {
            Kind.STRING -> "bundle.putString($key, $value)"
            Kind.INT -> "bundle.putInt($key, $value)"
            Kind.LONG -> "bundle.putLong($key, $value)"
            Kind.FLOAT -> "bundle.putFloat($key, $value)"
            Kind.DOUBLE -> "bundle.putDouble($key, $value)"
            Kind.BOOLEAN -> "bundle.putBoolean($key, $value)"
            Kind.ENUM -> "bundle.putString($key, $value.name)"
            Kind.STRING_LIST -> "bundle.putStringArrayList($key, ArrayList($value))"
            Kind.INT_LIST -> "bundle.putIntegerArrayList($key, ArrayList($value))"
            Kind.LONG_LIST -> "bundle.putLongArrayList($key, ArrayList($value))"
            Kind.POJO -> "TRouterPojo_${f.nestedSimpleName}!!.pack(bundle, $key + \".\", $value)"
        }
    }

    private fun unpackExpr(f: Field, key: String): String = when (f.kind) {
        Kind.STRING -> if (f.nullable) "bundle.getString($key)" else "bundle.getString($key) ?: \"\""
        Kind.INT -> "bundle.getInt($key)"
        Kind.LONG -> "bundle.getLong($key)"
        Kind.FLOAT -> "bundle.getFloat($key)"
        Kind.DOUBLE -> "bundle.getDouble($key)"
        Kind.BOOLEAN -> "bundle.getBoolean($key)"
        Kind.ENUM -> "${f.enumShortName}.valueOf(bundle.getString($key) ?: ${f.enumShortName}.values().first().name)"
        Kind.STRING_LIST -> "bundle.getStringArrayList($key)?.toList() ?: emptyList()"
        Kind.INT_LIST -> "bundle.getIntegerArrayList($key)?.toList() ?: emptyList()"
        Kind.LONG_LIST -> "bundle.getLongArrayList($key)?.toList() ?: emptyList()"
        Kind.POJO -> "TRouterPojo_${f.nestedSimpleName}!!.unpack(bundle, $key + \".\")"
    }

    /** 模块级注册表：按类名取 codec（供类型化代理等框架侧能力使用）。 */
    private fun emitRegistry(models: List<PojoModel>, sources: Array<KSFile>) {
        val entries = models.joinToString(",\n        ") { "TRouterPojo_${it.simpleName}" }
        val content = """
            |// 本文件由 TRouter KSP 处理器生成，请勿手改。
            |package $modulePackage.generated
            |
            |import com.trouter.core.api.TRouterPojoCodec
            |
            |/**
            | * 本模块 @RemotePojo 编解码器注册表：按类名取 codec，框架侧无需知道具体类型。
            | */
            |object TRouterPojoRegistry {
            |
            |    private val codecs: List<TRouterPojoCodec> = listOf(
            |        $entries,
            |    )
            |
            |    private val byName: Map<String, TRouterPojoCodec> = codecs.associateBy { it.className }
            |
            |    fun codecOf(className: String): TRouterPojoCodec? = byName[className]
            |
            |    fun all(): List<TRouterPojoCodec> = codecs
            |}
            |
        """.trimMargin()
        val deps = Dependencies(aggregating = true, *sources)
        codeGenerator.createNewFile(deps, "$modulePackage.generated", "TRouterPojoRegistry").use {
            it.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    private companion object {
        const val REMOTE_POJO_SHORT = "RemotePojo"
    }
}
