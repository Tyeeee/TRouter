package com.trouter.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

/**
 * 批次 A2 用例：调用点硬编码 path 必须被拦，引用常量必须放行。
 *
 * 用 Lint 官方测试底座（lint-tests）在 JVM 上直接跑检测器，不依赖模拟器：
 * 1) 字面量 → 期望 1 条 `TRouterHardcodedPath` 错误；
 * 2) 常量引用 → 期望零命中（反向对照，防误报）；
 * 3) 非 TRouter 接收者同名方法 → 期望零命中（防误伤）；
 * 4) `@Suppress` → 期望豁免生效（逃生通道）。
 */
class RoutePathLiteralDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = RoutePathLiteralDetector()

    override fun getIssues(): List<Issue> = listOf(RoutePathLiteralDetector.ISSUE)

    fun testHardcodedPathIsReported() {
        lint().files(STUB_TRouter, literalCall()).allowMissingSdk().run()
            .expectErrorCount(1)
            .expectContains("TRouterHardcodedPath")
            .expectContains("请引用 RouterContract 常量")
    }

    /** Kotlin：无默认参数的方法调用（排除 $default 合成方法干扰）。 */
    fun testKotlinCallWithoutDefaultArgsIsReported() {
        val src = kotlin(
            """
            package com.demo

            import com.trouter.core.api.TRouter

            object Entry2 {
                fun close() {
                    TRouter.unregisterRoute("/second")
                }
            }
            """,
        ).indented()
        lint().files(STUB_TRouter, src).allowMissingSdk().run().expectErrorCount(1)
    }

    /** Kotlin：显式传全部实参（不触发 $default 合成方法）。 */
    fun testKotlinCallWithExplicitArgsIsReported() {
        val src = kotlin(
            """
            package com.demo

            import com.trouter.core.api.TRouter

            object Entry3 {
                fun open() {
                    TRouter.navigate("/second", null)
                }
            }
            """,
        ).indented()
        lint().files(STUB_TRouter, src).allowMissingSdk().run().expectErrorCount(1)
    }

    fun testConstantPathIsClean() {
        lint().files(STUB_TRouter, constantCall()).allowMissingSdk().run().expectClean()
    }

    /** Java 调用点同样要命中（本库与多数接入方都是 Kotlin，但 Java 调用点不能漏）。 */
    fun testJavaHardcodedPathIsReported() {
        lint().files(java(STUB_JAVA_TRouter).indented(), java(JAVA_CALLER).indented()).allowMissingSdk().run()
            .expectErrorCount(1)
    }

    fun testOtherReceiverIsIgnored() {
        lint().files(STUB_TRouter, otherReceiverCall()).allowMissingSdk().run().expectClean()
    }

    fun testSuppressWorks() {
        lint().files(STUB_TRouter, suppressedCall()).allowMissingSdk().run().expectClean()
    }

    // ---------------------------------------------------------------- 测试输入

    /** 桩：让用例自带被检查的 API，不依赖真实模块，保证用例可独立运行。 */
    private fun literalCall(): TestFile = kotlin(
        """
        package com.demo

        import com.trouter.core.api.TRouter

        object Entry {
            fun open() {
                TRouter.navigate("/second")
            }
        }
        """,
    ).indented()

    private fun constantCall(): TestFile = kotlin(
        """
        package com.demo

        import com.trouter.core.api.RouterContract
        import com.trouter.core.api.TRouter

        object Entry {
            fun open() {
                TRouter.navigate(RouterContract.PATH_SECOND)
            }
        }
        """,
    ).indented()

    private fun otherReceiverCall(): TestFile = kotlin(
        """
        package com.demo

        object NotTRouter {
            fun navigate(path: String) {}
        }

        object Entry {
            fun open() {
                NotTRouter.navigate("/second")
            }
        }
        """,
    ).indented()

    private fun suppressedCall(): TestFile = kotlin(
        """
        package com.demo

        import com.trouter.core.api.TRouter

        object Entry {
            @Suppress("TRouterHardcodedPath")
            fun open() {
                TRouter.navigate("/second")
            }
        }
        """,
    ).indented()

    private companion object {
        private val STUB_JAVA_TRouter =
            """
            package com.trouter.core.api;

            public class TRouter {
                public static void navigate(String path) {}
            }
            """

        private val JAVA_CALLER =
            """
            package com.demo;

            import com.trouter.core.api.TRouter;

            public class Entry {
                public void open() {
                    TRouter.navigate("/second");
                }
            }
            """

        private val STUB_TRouter = kotlin(
            """
            package com.trouter.core.api

            object TRouter {
                fun navigate(path: String, extra: String? = null) {}
                fun navigateForResult(path: String, requestCode: Int, extra: String? = null) {}
                fun navigateRemote(path: String, extra: String? = null, onResult: (Int) -> Unit) {}
                fun unregisterRoute(path: String): Boolean = true
                fun registerRouteAlias(alias: String, toPath: String): Boolean = true
            }

            object RouterContract {
                const val PATH_SECOND: String = "/second"
            }
            """,
        ).indented()
    }
}
