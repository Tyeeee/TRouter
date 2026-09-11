package com.trouter.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULiteralExpression

/**
 * 路由 path 硬编码检查（批次 A2，issue id = `TRouterHardcodedPath`）。
 *
 * 覆盖范围：**调用点**。`TRouter.navigate("/second")` 这类写法在评审里最容易被放过去，
 * KSP 也看不见（它只看注解），运行期更不会报错——只是"改路径时漏改一处"的定时炸弹。
 *
 * 判定规则（保守，宁缺勿误报）：
 * - 只认接收者是 `com.trouter.core.api.TRouter` 的调用；
 * - 只认方法名在白名单内的调用（navigate / navigateForResult / navigateRemote / unregisterRoute / registerRouteAlias）；
 * - 只认**源码里写死的字符串字面量**且以 `/` 开头（引用常量不报、字符串插值不报、变量不报）；
 * - 全部命中才报 error，可用 `@Suppress("TRouterHardcodedPath")` 逐处豁免。
 *
 * 实现踩过的三个坑（都写进用例防回归，避免后人重踩）：
 * 1. **不能靠 `getApplicableMethodNames` 过滤**：Kotlin 带默认参数的调用会解析到合成方法
 *    `navigate$default`，按名字过滤会整体失效（`testKotlinCallWithoutDefaultArgsIsReported` 系列实测确认）；
 * 2. **Kotlin 的字符串字面量不是 `ULiteralExpression`**：普通字面量在 UAST 里是
 *    `KotlinStringTemplateUPolyadicExpression`，只按类型判断会漏报全部 Kotlin 调用点
 *    （本规则曾因此"Java 报、Kotlin 不报"）；因此改判**源码文本**：带引号且不含 `$` 才算字面量；
 * 3. **不能用常量求值**（`ConstantEvaluator`）来判断：它会把 `RouterContract.PATH_SECOND` 也算成
 *    `"/second"`，反而把合规写法误报成违规——本规则要守的恰恰是"引用常量 = 合规"。
 */
class RoutePathLiteralDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitCallExpression(node: UCallExpression) {
                val method = node.resolve() ?: return
                if (!isTRouterMethod(method)) return
                for (arg in node.valueArguments) {
                    val path = hardcodedStringOf(arg) ?: continue
                    if (!path.startsWith("/")) continue
                    context.report(
                        ISSUE,
                        arg,
                        context.getLocation(arg),
                        "路由 path 不要硬编码字符串（\"$path\"）：请引用 RouterContract 常量。" +
                            "KSP 只能检查 @Route 注解，调用点由本规则守住；" +
                            "确需临时写死请加 @Suppress(\"TRouterHardcodedPath\")。",
                    )
                }
            }
        }

    /** 方法是否属于 TRouter 且在白名单内（Kotlin 合成方法先剥掉 `$default` 后缀）。 */
    private fun isTRouterMethod(method: PsiMethod): Boolean {
        val owner = method.containingClass?.qualifiedName ?: return false
        if (owner != TR_OUTER_CLASS) return false
        return method.name.substringBefore('$') in CALL_NAMES
    }

    /**
     * 实参是否为**源码写死的字符串字面量**；是则返回其内容（不含引号）。
     * Java 走 `ULiteralExpression`；Kotlin 看源码文本（排除插值/常量/变量）。
     */
    private fun hardcodedStringOf(arg: UExpression): String? {
        val literal = (arg as? ULiteralExpression)?.value
        if (literal is String) return literal

        val text = arg.sourcePsi?.text?.trim() ?: return null
        if (text.length < 2) return null
        if (!text.startsWith("\"") || !text.endsWith("\"")) return null
        if (text.contains('$')) return null // 字符串模板/插值：内容运行期才确定，不算硬编码
        return text.substring(1, text.length - 1)
    }

    companion object {
        private const val TR_OUTER_CLASS = "com.trouter.core.api.TRouter"

        /** 只覆盖"参数就是 path"的方法，避免把服务名、别名等误判成路径。 */
        private val CALL_NAMES = listOf(
            "navigate",
            "navigateForResult",
            "navigateRemote",
            "unregisterRoute",
            "registerRouteAlias",
        )

        val ISSUE: Issue = Issue.create(
            id = "TRouterHardcodedPath",
            briefDescription = "路由 path 硬编码字符串",
            explanation = """
                TRouter 的路径契约要求所有 path 引用 RouterContract 常量。
                @Route 注解里的字面量由 KSP 处理器分级校验（warning/error），
                但调用点的字面量（如 TRouter.navigate("/second")）KSP 看不到，
                必须由本 Lint 规则在编译/CI 阶段拦住，否则改路径时会漏改调用方，
                最终在运行期表现为「打开了错误页面」或「路由未找到」。
            """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(
                RoutePathLiteralDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
