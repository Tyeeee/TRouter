package com.trouter.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType

/**
 * `@RemoteApi` 生成器（多进程与跨进程增强）：把接口变成"可跨进程调用的类型化 API"。
 *
 * 为接口生成 `TRouterRemoteApi_<接口名>`（实现 `TRouterRemoteApiCodec`）：
 * - `methods`：每个方法的参数/结果编解码器（客户端用它把实参打包、把回包解出结果）；
 * - `registerClient()`：客户端进程一行注册编解码器；
 * - `register(impl)`：远端进程登记实现，内部按方法名分发并**等待实现回调**（超时兜底）。
 *
 * 接口方法约定：最后一个参数必须是 `(T) -> Unit` 回调；其余参数与 T 走统一类型白名单
 * （见 [RemoteTypeClassifier]）。远端实现侧的分发会在 binder 线程等待回调完成
 * （超时 [DISPATCH_TIMEOUT_MS]），这样远端实现可以自由地用异步方式产出结果。
 */
internal class RemoteApiEmitter(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val modulePackage: String,
) {

    private val classifier = RemoteTypeClassifier(logger, modulePackage)
    private val emitted = LinkedHashSet<String>()

    private data class MethodModel(
        val name: String,
        val params: List<Pair<String, RemoteTypeClassifier.Info>>,
        val result: RemoteTypeClassifier.Info,
    )

    private data class ApiModel(
        val iface: KSClassDeclaration,
        val className: String,
        val simpleName: String,
        val apiName: String,
        val methods: List<MethodModel>,
    )

    fun emit(interfaces: List<KSClassDeclaration>) {
        val models = ArrayList<ApiModel>()
        for (decl in interfaces) {
            val fqcn = decl.qualifiedName?.asString() ?: continue
            if (!emitted.add(fqcn)) continue
            parse(decl, fqcn)?.let { models.add(it) }
        }
        if (models.isEmpty()) return
        val sources: Array<KSFile> = models.mapNotNull { it.iface.containingFile }.distinct().toTypedArray()
        models.forEach { emitOne(it, sources) }
        emitRegistry(models, sources)
    }

    // ------------------------------------------------------------------ 解析

    private fun parse(decl: KSClassDeclaration, fqcn: String): ApiModel? {
        if (decl.classKind != com.google.devtools.ksp.symbol.ClassKind.INTERFACE) {
            logger.error("@RemoteApi 只能标注在 interface 上：$fqcn", decl)
            return null
        }
        val apiName = decl.annotations.firstOrNull { it.shortName.asString() == REMOTE_API_SHORT }
            ?.arguments?.firstOrNull { it.name?.asString() == "name" }?.value as? String
        val methods = ArrayList<MethodModel>()
        var ok = true

        for (fn in decl.declarations.filterIsInstance<KSFunctionDeclaration>()) {
            val methodName = fn.simpleName.asString()
            if (methodName == "equals" || methodName == "hashCode" || methodName == "toString") continue
            val params = fn.parameters
            val callback = params.lastOrNull()
            if (params.isEmpty() || callback == null) {
                logger.error("@RemoteApi 方法必须有 (结果) -> Unit 回调参数：$fqcn.$methodName", fn)
                ok = false
                continue
            }
            val callbackType = callback.type.resolve()
            // Kotlin 的 (T) -> Unit 在 KSP 里是 kotlin.Function1<T, Unit>：两个泛型实参
            val callbackDecl = callbackType.declaration.qualifiedName?.asString()
            val callbackArgs = callbackType.arguments
            if (callbackDecl != "kotlin.Function1" || callbackArgs.size != 2) {
                logger.error(
                    "@RemoteApi 方法的最后一个参数必须是单参回调 (T) -> Unit：$fqcn.$methodName" +
                        "（当前是 $callbackDecl）",
                    fn,
                )
                ok = false
                continue
            }
            val callbackReturn = callbackArgs[1].type?.resolve()?.declaration?.qualifiedName?.asString()
            if (callbackReturn != "kotlin.Unit") {
                logger.error(
                    "@RemoteApi 回调不能有返回值（应为 (T) -> Unit）：$fqcn.$methodName",
                    fn,
                )
                ok = false
                continue
            }
            if (fn.returnType?.resolve()?.declaration?.qualifiedName?.asString() != "kotlin.Unit") {
                logger.error("@RemoteApi 方法必须返回 Unit（结果通过回调给出）：$fqcn.$methodName", fn)
                ok = false
                continue
            }

            val resultType: KSType = callbackArgs.first().type?.resolve() ?: run {
                logger.error("@RemoteApi 回调缺少结果类型：$fqcn.$methodName", fn)
                ok = false
                null
            } ?: continue

            val result = classifier.classify(resultType, methodName, fqcn, fn, "回调结果")
            if (result == null) {
                ok = false
                continue
            }
            val parsedParams = ArrayList<Pair<String, RemoteTypeClassifier.Info>>()
            for (p in params.dropLast(1)) {
                val pName = p.name?.asString() ?: "arg${parsedParams.size}"
                val info = classifier.classify(p.type.resolve(), pName, fqcn, fn, "参数") ?: run {
                    ok = false
                    null
                } ?: continue
                parsedParams.add(pName to info)
            }
            methods.add(MethodModel(methodName, parsedParams, result))
        }

        if (!ok || methods.isEmpty()) {
            if (methods.isEmpty() && ok) logger.error("@RemoteApi 接口至少要有一个可分发方法：$fqcn", decl)
            return null
        }
        return ApiModel(decl, fqcn, decl.simpleName.asString(), apiName?.takeIf { it.isNotBlank() } ?: fqcn, methods)
    }

    // ------------------------------------------------------------------ 生成

    private fun emitOne(model: ApiModel, sources: Array<KSFile>) {
        val codecObjects = StringBuilder()
        val methodEntries = StringBuilder()
        val dispatchBranches = StringBuilder()
        val dispatchFns = StringBuilder()

        for ((index, m) in model.methods.withIndex()) {
            val cap = m.name.replaceFirstChar { it.uppercase() }
            val codecName = "${cap}Codec"
            if (index > 0) methodEntries.append(",\n")
            methodEntries.append("        \"${m.name}\" to $codecName")

            // ---- 方法编解码器（客户端）
            val encodeBody = StringBuilder()
            m.params.forEachIndexed { i, (pName, info) ->
                encodeBody.appendLine("            ${putStatement(info, "\"a$i\"", "(values[$i] as ${kotlinTypeName(info)})", "args")}")
            }
            if (m.params.isEmpty()) encodeBody.appendLine("            // 无参方法：无需编码")

            codecObjects.appendLine("    private object $codecName : TRouterRemoteMethodCodec {")
            codecObjects.appendLine("        override fun encode(args: Bundle, values: List<Any?>) {")
            codecObjects.append(encodeBody)
            codecObjects.appendLine("        }")
            codecObjects.appendLine()
            codecObjects.appendLine("        override fun decode(reply: Bundle): Any? = ${getExpr(m.result, "\"r\"", "reply")}")
            codecObjects.appendLine("    }")
            codecObjects.appendLine()

            // ---- 远端分发（按方法名）
            dispatchBranches.appendLine("            \"${m.name}\" -> dispatch$cap(impl, args)")

            dispatchFns.appendLine("    /** ${m.name}：调用实现并等待其回调（binder 线程等待，超时见 DISPATCH_TIMEOUT_MS）。 */")
            dispatchFns.appendLine("    private fun dispatch$cap(impl: ${model.simpleName}, args: Bundle): Bundle {")
            dispatchFns.appendLine("        val latch = CountDownLatch(1)")
            dispatchFns.appendLine("        val ref = AtomicReference<Bundle?>()")
            val callArgs = m.params.mapIndexed { i, (_, info) -> getExpr(info, "\"a$i\"", "args") }.joinToString(", ")
            val lambdaArg = if (callArgs.isNotEmpty()) "$callArgs) { value ->" else ") { value ->"
            dispatchFns.appendLine("        impl.${m.name}($lambdaArg")
            dispatchFns.appendLine("            val reply = TRouterTypedReply.success()")
            if (m.result.kind != RemoteTypeClassifier.Kind.STRING_LIST &&
                m.result.kind != RemoteTypeClassifier.Kind.INT_LIST &&
                m.result.kind != RemoteTypeClassifier.Kind.LONG_LIST
            ) {
                dispatchFns.appendLine("            ${putStatement(m.result, "\"r\"", "value", "reply")}")
            } else {
                dispatchFns.appendLine("            ${putStatement(m.result, "\"r\"", "value", "reply")}")
            }
            dispatchFns.appendLine("            ref.set(reply)")
            dispatchFns.appendLine("            latch.countDown()")
            dispatchFns.appendLine("        }")
            dispatchFns.appendLine("        if (!latch.await(DISPATCH_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)) {")
            dispatchFns.appendLine(
                "            return TRouterTypedReply.failure(\"远端实现未在 \" + DISPATCH_TIMEOUT_MS + \"ms 内回调：${m.name}\")",
            )
            dispatchFns.appendLine("        }")
            dispatchFns.appendLine("        return ref.get() ?: TRouterTypedReply.failure(\"远端实现未返回结果：${m.name}\")")
            dispatchFns.appendLine("    }")
            dispatchFns.appendLine()
        }

        val imports = StringBuilder()
        imports.appendLine("import android.os.Bundle")
        imports.appendLine("import com.trouter.core.api.TRouter")
        imports.appendLine("import com.trouter.core.api.TRouterRemoteApiCodec")
        imports.appendLine("import com.trouter.core.api.TRouterRemoteMethodCodec")
        imports.appendLine("import com.trouter.core.api.TRouterTypedReply")
        imports.appendLine("import ${model.className}")
        imports.appendLine("import java.util.concurrent.CountDownLatch")
        imports.appendLine("import java.util.concurrent.TimeUnit")
        imports.appendLine("import java.util.concurrent.atomic.AtomicReference")
        model.methods.forEach { m ->
            (m.params.map { it.second } + m.result).forEach { info ->
                when (info.kind) {
                    RemoteTypeClassifier.Kind.ENUM -> imports.appendLine("import ${info.enumTypeName}")
                    else -> Unit
                }
            }
        }

        val content = """
            |// 本文件由 TRouter KSP 处理器生成，请勿手改。
            |package $modulePackage.generated
            |
            |$imports
            |/**
            | * @RemoteApi 生成物：${model.className}
            | * - 客户端进程：registerClient() 一行注册后即可 TRouter.remoteApi(${model.simpleName}::class.java, target)
            | * - 远端进程：register(impl) 登记实现，框架按方法名分发并在 binder 线程等待回调
            | */
            |object TRouterRemoteApi_${model.simpleName} : TRouterRemoteApiCodec {
            |
            |    override val apiName: String = "${model.apiName}"
            |
            |    override val apiClass: Class<*> = ${model.simpleName}::class.java
            |
            |    override val methods: Map<String, TRouterRemoteMethodCodec> = mapOf(
            |$methodEntries,
            |    )
            |
            |    /** 客户端进程注册（一行接入）。 */
            |    fun registerClient(): Boolean = TRouter.registerRemoteApiClients(listOf(this)) > 0
            |
            |    /** 远端进程登记实现。 */
            |    fun register(impl: ${model.simpleName}): Boolean = TRouter.registerRemoteApi(apiName) { method, args ->
            |        when (method) {
            |$dispatchBranches            else -> TRouterTypedReply.failure("远端未实现方法：${'$'}method")
            |        }
            |    }
            |
            |$codecObjects$dispatchFns    /** 远端实现在此时间内必须回调（超时按失败回包，避免 binder 线程长期占用）。 */
            |    private const val DISPATCH_TIMEOUT_MS = 5_000
            |}
            |
        """.trimMargin()

        val deps = Dependencies(aggregating = true, *sources)
        codeGenerator.createNewFile(deps, "$modulePackage.generated", "TRouterRemoteApi_${model.simpleName}").use {
            it.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    /** 参数/结果 → 写 Bundle 语句。 */
    private fun putStatement(
        info: RemoteTypeClassifier.Info,
        keyLiteral: String,
        value: String,
        bundle: String,
    ): String = when (info.kind) {
        RemoteTypeClassifier.Kind.STRING -> "$bundle.putString($keyLiteral, $value)"
        RemoteTypeClassifier.Kind.INT -> "$bundle.putInt($keyLiteral, $value)"
        RemoteTypeClassifier.Kind.LONG -> "$bundle.putLong($keyLiteral, $value)"
        RemoteTypeClassifier.Kind.FLOAT -> "$bundle.putFloat($keyLiteral, $value)"
        RemoteTypeClassifier.Kind.DOUBLE -> "$bundle.putDouble($keyLiteral, $value)"
        RemoteTypeClassifier.Kind.BOOLEAN -> "$bundle.putBoolean($keyLiteral, $value)"
        RemoteTypeClassifier.Kind.ENUM -> "$bundle.putString($keyLiteral, $value.name)"
        RemoteTypeClassifier.Kind.STRING_LIST -> "$bundle.putStringArrayList($keyLiteral, ArrayList($value))"
        RemoteTypeClassifier.Kind.INT_LIST -> "$bundle.putIntegerArrayList($keyLiteral, ArrayList($value))"
        RemoteTypeClassifier.Kind.LONG_LIST -> "$bundle.putLongArrayList($keyLiteral, ArrayList($value))"
        RemoteTypeClassifier.Kind.POJO ->
            "TRouterPojo_${info.nestedSimpleName}.pack($bundle, $keyLiteral + \".\", $value)"
    }

    /** 白名单类型 → Kotlin 类型名（用于把 List<Any?> 里的实参强转回声明类型）。 */
    private fun kotlinTypeName(info: RemoteTypeClassifier.Info): String = when (info.kind) {
        RemoteTypeClassifier.Kind.STRING -> "String"
        RemoteTypeClassifier.Kind.INT -> "Int"
        RemoteTypeClassifier.Kind.LONG -> "Long"
        RemoteTypeClassifier.Kind.FLOAT -> "Float"
        RemoteTypeClassifier.Kind.DOUBLE -> "Double"
        RemoteTypeClassifier.Kind.BOOLEAN -> "Boolean"
        RemoteTypeClassifier.Kind.ENUM -> info.enumShortName ?: "Any"
        RemoteTypeClassifier.Kind.STRING_LIST -> "List<String>"
        RemoteTypeClassifier.Kind.INT_LIST -> "List<Int>"
        RemoteTypeClassifier.Kind.LONG_LIST -> "List<Long>"
        RemoteTypeClassifier.Kind.POJO -> info.nestedSimpleName ?: "Any"
    }

    /** 参数/结果 → 读 Bundle 表达式。 */
    private fun getExpr(info: RemoteTypeClassifier.Info, keyLiteral: String, bundle: String): String = when (info.kind) {
        RemoteTypeClassifier.Kind.STRING ->
            if (info.nullable) "$bundle.getString($keyLiteral)" else "$bundle.getString($keyLiteral) ?: \"\""
        RemoteTypeClassifier.Kind.INT -> "$bundle.getInt($keyLiteral)"
        RemoteTypeClassifier.Kind.LONG -> "$bundle.getLong($keyLiteral)"
        RemoteTypeClassifier.Kind.FLOAT -> "$bundle.getFloat($keyLiteral)"
        RemoteTypeClassifier.Kind.DOUBLE -> "$bundle.getDouble($keyLiteral)"
        RemoteTypeClassifier.Kind.BOOLEAN -> "$bundle.getBoolean($keyLiteral)"
        RemoteTypeClassifier.Kind.ENUM ->
            "${info.enumShortName}.valueOf($bundle.getString($keyLiteral) ?: ${info.enumShortName}.values().first().name)"
        RemoteTypeClassifier.Kind.STRING_LIST -> "$bundle.getStringArrayList($keyLiteral)?.toList() ?: emptyList()"
        RemoteTypeClassifier.Kind.INT_LIST -> "$bundle.getIntegerArrayList($keyLiteral)?.toList() ?: emptyList()"
        RemoteTypeClassifier.Kind.LONG_LIST -> "$bundle.getLongArrayList($keyLiteral)?.toList() ?: emptyList()"
        RemoteTypeClassifier.Kind.POJO -> "TRouterPojo_${info.nestedSimpleName}.unpack($bundle, $keyLiteral + \".\")"
    }

    private fun emitRegistry(models: List<ApiModel>, sources: Array<KSFile>) {
        val entries = models.joinToString(",\n        ") { "TRouterRemoteApi_${it.simpleName}" }
        val content = """
            |// 本文件由 TRouter KSP 处理器生成，请勿手改。
            |package $modulePackage.generated
            |
            |import com.trouter.core.api.TRouterRemoteApiCodec
            |
            |/** 本模块 @RemoteApi 生成物注册表：客户端一行注册全部编解码器。 */
            |object TRouterRemoteApiRegistry {
            |
            |    fun all(): List<TRouterRemoteApiCodec> = listOf(
            |        $entries,
            |    )
            |}
            |
        """.trimMargin()
        val deps = Dependencies(aggregating = true, *sources)
        codeGenerator.createNewFile(deps, "$modulePackage.generated", "TRouterRemoteApiRegistry").use {
            it.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    private companion object {
        const val REMOTE_API_SHORT = "RemoteApi"
    }
}
