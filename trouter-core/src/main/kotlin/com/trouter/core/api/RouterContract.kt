package com.trouter.core.api

/**
 * 统一路由契约（单一来源）。
 *
 * 所有 @Route 的 path 必须引用本常量，禁止硬编码字符串；
 * KSP 检测到字面量时会输出 Warning。
 *
 * 演进登记（版本回顾见 docs/版本回顾与缺口核查.md）：
 * - V1.0：沉淀演示宿主路径 /main /second /fragment-demo /about /not/exist（页面当时全在 :app）；
 * - V2.0：新增 /mock/second（Mock 拦截器演示目标）；
 * - V3.0：路径常量保持 core 单一来源不变，页面按功能迁入 :feature-demo / :feature-about 两个模块；
 * - V4.0：新增 /remote-second（跨进程演示目标，@CrossProcess 白名单）；
 * - V5.0：新增 /dynamic-demo（动态路由演示目标——页面不标 @Route，运行时 registerRoute 注册）。
 */
object RouterContract {

    /** 默认分组名（与 @Route.DEFAULT_GROUP 语义对齐的单一来源）。 */
    const val GROUP_DEFAULT: String = "default"

    /** 分组路由演示：secondary group（About 页）。 */
    const val GROUP_SECONDARY: String = "secondary"

    /** Mock 拦截器演示分组（Mock 页）。 */
    const val GROUP_MOCK: String = "mock"

    /** 动态路由演示分组（运行时 registerRoute 使用）。 */
    const val GROUP_DYNAMIC: String = "dynamic"

    /** 主页（Launcher，同时注册为可路由页面） */
    const val PATH_MAIN: String = "/main"

    /** 二级页面（testBasicNavigation 目标） */
    const val PATH_SECOND: String = "/second"

    /** Fragment 目标演示（FRAGMENT kind，经 core FragmentContainerActivity 承载） */
    const val PATH_FRAGMENT_DEMO: String = "/fragment-demo"

    /** 分组路由演示：About 页，注册在 secondary group（验证多 group 加载器生成） */
    const val PATH_ABOUT: String = "/about"

    /** V2.0 Mock 演示：/second 的 Mock 替身页（MockInterceptor 重定向目标，group=mock） */
    const val PATH_MOCK_SECOND: String = "/mock/second"

    /** V4.0 跨进程演示：remote 进程页面（@Route + @CrossProcess，manifest 声明 android:process=":remote"） */
    const val PATH_REMOTE_SECOND: String = "/remote-second"

    /** V5.0 动态路由演示：/dynamic-demo（运行时 registerRoute 注册；页面本身不标 @Route） */
    const val PATH_DYNAMIC_DEMO: String = "/dynamic-demo"

    /**
     * 刻意未注册的路径，仅用于演示/测试 NotFound 降级（testLostNavigation）。
     * 注意：本常量不允许被任何 @Route 引用。
     */
    const val PATH_UNREGISTERED: String = "/not/exist"
}
