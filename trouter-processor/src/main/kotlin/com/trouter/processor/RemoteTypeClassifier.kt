package com.trouter.processor

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Variance

/**
 * 跨进程可用类型的**统一判定**（多进程与跨进程增强）。
 *
 * 由 `@RemotePojo` 字段与 `@RemoteApi` 方法参数/返回值共用，保证两处的"支持什么类型"
 * 完全一致（不出现两套白名单各说各话）。
 *
 * 白名单：String / Int / Long / Float / Double / Boolean、枚举（按 name）、
 * `List<String>` / `List<Int>` / `List<Long>`、以及**同模块**内另一个 @RemotePojo 类。
 * 其余类型一律编译报错（不静默降级）。
 */
internal class RemoteTypeClassifier(
    private val logger: KSPLogger,
    private val modulePackage: String,
) {

    enum class Kind { STRING, INT, LONG, FLOAT, DOUBLE, BOOLEAN, ENUM, STRING_LIST, INT_LIST, LONG_LIST, POJO }

    data class Info(
        val kind: Kind,
        val nullable: Boolean,
        val nestedSimpleName: String? = null,
        val enumTypeName: String? = null,
        val enumShortName: String? = null,
    )

    /**
     * @param what 出错时的措辞（如 "字段" / "参数" / "返回值"）
     * @return null 表示已报错（调用方应放弃生成）
     */
    fun classify(type: KSType, name: String, owner: String, decl: KSDeclaration, what: String): Info? {
        val nullable = type.isMarkedNullable
        val desc = type.declaration.qualifiedName?.asString() ?: type.declaration.simpleName.asString()

        fun fail(): Info? {
            logger.error(
                "@RemotePojo/@RemoteApi 不支持的类型：$owner.$name（$what）的类型是 $desc。" +
                    "支持：String/Int/Long/Float/Double/Boolean、枚举、" +
                    "List<String>/List<Int>/List<Long>、同模块内另一个 @RemotePojo 类；" +
                    "其他类型请先在业务侧转换为白名单类型。",
                decl,
            )
            return null
        }

        if (desc.startsWith("kotlin.collections.List")) {
            if (desc != "kotlin.collections.List") return fail() // MutableList/ArrayList 等
            val arg = type.arguments.singleOrNull() ?: return fail()
            if (arg.variance == Variance.STAR) return fail()
            val argType = arg.type?.resolve() ?: return fail()
            return when (argType.declaration.qualifiedName?.asString()) {
                "kotlin.String" -> Info(Kind.STRING_LIST, false)
                "kotlin.Int" -> Info(Kind.INT_LIST, false)
                "kotlin.Long" -> Info(Kind.LONG_LIST, false)
                else -> fail()
            }
        }

        return when (desc) {
            "kotlin.String" -> Info(Kind.STRING, nullable)
            "kotlin.Int" -> Info(Kind.INT, nullable)
            "kotlin.Long" -> Info(Kind.LONG, nullable)
            "kotlin.Float" -> Info(Kind.FLOAT, nullable)
            "kotlin.Double" -> Info(Kind.DOUBLE, nullable)
            "kotlin.Boolean" -> Info(Kind.BOOLEAN, nullable)
            else -> {
                val target = type.declaration as? KSClassDeclaration ?: return fail()
                when {
                    target.classKind == ClassKind.ENUM_CLASS -> Info(
                        Kind.ENUM,
                        nullable,
                        enumTypeName = desc,
                        enumShortName = target.simpleName.asString(),
                    )
                    target.annotations.any { it.shortName.asString() == REMOTE_POJO_SHORT } ->
                        if (target.packageName.asString() != modulePackage) {
                            logger.error(
                                "@RemotePojo 嵌套/引用必须与本类在**同一模块**：$owner.$name → $desc" +
                                    "（跨模块引用会找不到对方的生成物；请把相关 POJO 收敛到同一模块）",
                                decl,
                            )
                            null
                        } else {
                            Info(Kind.POJO, nullable, nestedSimpleName = target.simpleName.asString())
                        }
                    else -> fail()
                }
            }
        }
    }

    private companion object {
        const val REMOTE_POJO_SHORT = "RemotePojo"
    }
}
