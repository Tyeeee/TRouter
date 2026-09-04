package com.trouter.core.testing

import androidx.test.core.app.ApplicationProvider
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterConfig
import org.junit.After
import org.junit.Before

/**
 * 测试底座基类（core debug 变体，只依赖 core 接口，不感知任何 :app 生成类）。
 *
 * - [provideRegistry] 由 :app 的 androidTest 子类实现，返回其 KSP 生成物
 *   （如 TRouterGroupRegistry）—— 生成类在 :app 模块，core:debug 编译时不可见；
 * - @Before：重置路由状态 → TRouter.init(context, createConfig()) → install(provideRegistry())；
 * - @After：释放资源，保证用例间零污染（T2）；
 * - [reInit] 供同一条用例内切换配置（如 testConfigDebugMode 的 debug=false 阶段）。
 */
abstract class BaseTRouterTest {

    /** 日志收集器：断言 [TRouter] 输出（O1 / R3）。 */
    protected val logs = LogRecorder()

    /** onLost 记录器：断言降级回调收到哪些 path（R2）。 */
    protected val lostPaths = mutableListOf<String>()

    /** @return 测试宿主（:app）的 KSP 生成注册表实例。 */
    protected abstract fun provideRegistry(): GroupLoaderRegistry

    @Before
    fun setUp() {
        // 先清空，再 init/install：install 产生的 GroupLoader 加载起止日志（O1）保留在本用例记录中
        logs.clear()
        lostPaths.clear()
        TRouter.resetForTest()
        TRouter.init(ApplicationProvider.getApplicationContext(), createConfig())
        TRouter.install(provideRegistry())
    }

    @After
    fun tearDown() {
        TRouter.resetForTest()
        logs.clear()
        lostPaths.clear()
    }

    /** 默认配置：isDebug=true + 日志收集 + onLost 记录。子类可覆写。 */
    protected open fun createConfig(): TRouterConfig =
        TestConfig(isDebug = true, logSink = logs, onLost = { lostPaths += it })

    /** 用例内切换配置：reset → init(新配置) → install(provideRegistry())。 */
    protected fun reInit(config: TRouterConfig) {
        logs.clear()
        lostPaths.clear()
        TRouter.resetForTest()
        TRouter.init(ApplicationProvider.getApplicationContext(), config)
        TRouter.install(provideRegistry())
    }

    /**
     * 仅重置为"未初始化"状态（供 API 契约测试验证 NotInitialized 等分支）。
     * 注意：重置后本用例内如需导航必须自行 reInit/createConfig 后再 init。
     */
    protected fun resetRouterState() {
        TRouter.resetForTest()
        logs.clear()
        lostPaths.clear()
    }
}
