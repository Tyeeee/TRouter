# TRouter —— Android 组件化路由框架（开发对接文档）

> 本文是「怎么把 TRouter 用起来」的对接文档，写给要接入的 Android 开发者。
> 全文按当前代码真实状态编写：示例都可在本仓库直接找到对应实现，不写框架里不存在的能力。
> 仓库自带一个**自描述演示工程**（主页场景 S01–S22），配合本文一起看，效果最好。

---

## 目录

1. [TRouter 是什么](#1-trouter-是什么)
2. [核心设计：四个“统一”](#2-核心设计四个统一)
3. [功能清单](#3-功能清单)
4. [模块与生成物](#4-模块与生成物)
5. [环境要求](#5-环境要求)
6. [快速接入（最小可用）](#6-快速接入最小可用)
7. [导航](#7-导航)
8. [拦截器](#8-拦截器)
9. [跨进程 · 第二进程页面与服务](#9-跨进程-第二进程页面与服务)
10. [动态路由与路由表热更](#10-动态路由与路由表热更)
11. [进程内服务层](#11-进程内服务层)
12. [深链（URI 唤起）](#12-深链uri-唤起)
13. [可观测性](#13-可观测性)
14. [编译期保障](#14-编译期保障)
15. [一个完整接入例子](#15-一个完整接入例子)
16. [常见问题（FAQ）](#16-常见问题faq)
17. [演示工程怎么跑](#17-演示工程怎么跑)
18. [路线图与当前状态（诚实声明）](#18-路线图与当前状态诚实声明)
19. [文档索引](#19-文档索引)

---

## 1. TRouter 是什么

TRouter 是一个**纯自研**的 Android 组件化路由框架：

- **页面不互相依赖**：模块 A 想打开模块 B 的页面，不 import B 的类，只认一个“路径字符串”。
- **路径在编译期登记**：用注解标注页面，KSP 在编译期收集成路由表，启动后直接查表跳转。不用反射扫包、不影响启动性能，页面类直到真正跳转那一刻才加载。
- **“四个统一”收口治理**：见下一节。

一句话：**把“模块间跳转 + 跳转前的各种检查 + 特殊页面（第二进程 / 动态下发）打开”这件事，收敛成一个入口、一套配置、一种结果、一份路径契约。**

> 💡 你没看错，这不是包装 ARouter，是从零写的实现（参照了开源方案的能力清单，见
> `docs/开源Router调研.md` 与 `docs/我们与开源Router差距分析.md`）。

---

## 2. 核心设计：四个“统一”

这是全库的**设计红线**，接入方也应按同一纪律写业务代码：

| 统一 | 内容 | 违反的后果 |
|---|---|---|
| **统一入口** | 对外能力全部从单例 `TRouter` 暴露 | 能力散落、难以治理 |
| **统一配置** | 所有开关集中在 `TRouterConfig` 一次注入 | 全局变量满天飞 |
| **统一结果** | 导航永远返回密封类 `TRouterResult`，**没有 null** | 调用方漏判失败分支 |
| **统一契约** | 路径常量只写在 `RouterContract` | 字符串写死、改一处漏一处 |

配套两条纪律：

- **路径必须引用 `RouterContract` 常量**，不写 `"/second"` 这种字面量（KSP 会告警，见[第 14 节](#14-编译期保障)）；
- **拦截器等所有“链上行为”可开关、可观察、可测试**，不许悄悄发生。

---

## 3. 功能清单

| 能力 | 说明 | 对应章节 |
|---|---|---|
| Activity / Fragment 导航 | `@Route` 标注页面，按 path 打开；Fragment 自动套 core 容器页 | [7](#7-导航) |
| 统一结果 + 兜底回调 | `TRouterResult` 四种结果；`onLost` 兜底“没找到/打开失败” | [7.1](#71-结果语义与兜底回调) |
| 参数透传 + 收参助手 | 发：`Bundle`；收：`RouteArgs`（不用反射） | [7.3](#73-参数传递) |
| 导航结果回传 | `navigateForResult`（对齐系统 `startActivityForResult` 语义） | [7.5](#75-带结果回传的导航-g3) |
| 拦截器链 | 全局注入 + 运行时增删；原子型 / 洋葱包裹型两种形态 | [8](#8-拦截器) |
| Mock / 门禁拦截 | core 内置 `MockInterceptor`；业务自写 `RouteInterceptor` | [8.2](#82-一个最小拦截器) / [8.5](#85-core-内置-mock-拦截器) |
| 目标级拦截器 | `@Interceptor(names)` 给指定页面挂拦截器 | [8.8](#88-目标级拦截器-l3) |
| 跨进程导航 | `@CrossProcess` 白名单 + AIDL 通道，第二进程页由**它自己的 TRouter** 打开 | [9](#9-跨进程-第二进程页面与服务) |
| 跨进程服务 | 第二进程注册端点，host 异步调用（`callRemoteService`） | [9.5](#95-跨进程服务调用-g2-remote) |
| 动态路由 | 运行时注册/注销，与静态路由同表共存；可持久化、可原子热更 | [10](#10-动态路由与路由表热更) |
| 路由表 JSON | 全表导出；导入为“动态覆盖层”（原子替换、失败零变更） | [10.4](#104-路由表-json-g6) |
| 路由别名 | 精确别名 + 正则别名 | [10.5](#105-路由别名-g5) |
| 路由图谱 | 实时导出“目标类 ↔ path”图模型 | [10.6](#106-路由图谱-v5) |
| 深链 | `navigateUri` + scheme 白名单 | [12](#12-深链uri-唤起) |
| 进程内服务 | 接口→实现注册与查找，页面/路由解耦 | [11](#11-进程内服务层) |
| 路由目标体检 | `checkRouteTargets()` 找出“注册了但类加载不了”的路由 | [10.7](#107-路由目标体检-g7) |
| 可观测性 | 页面级证据横幅、结构化日志（Timber）、traceId 贯穿 | [13](#13-可观测性) |
| 编译期保障 | 路径冲突编译报错、契约字面量告警、跨模块冲突扫描 | [14](#14-编译期保障) |

> ⚠️ **诚实声明**：`G9 模块自动初始化`与`G10 Action 全局事件`目前**尚未实现**，本文不提供其用法，
> 也不在任何地方暗示可用。见[第 18 节](#18-路线图与当前状态诚实声明)。

---

## 4. 模块与生成物

### 4.1 仓库模块

| 模块 | 作用 | 谁依赖它 |
|---|---|---|
| `trouter-annotation` | 注解：`@Route`、`@CrossProcess`、`@Interceptor`（纯 Kotlin JVM 模块） | 所有声明路由的模块 |
| `trouter-processor` | KSP 处理器：扫描注解 → 生成分组加载器 / 白名单 / 拦截器映射 | 所有声明路由的模块（`ksp(...)`） |
| `trouter-core` | 运行时：`TRouter` 单例、路由表、拦截器链、Fragment 容器页、AIDL 通道与 RemoteRouterService、测试底座 | 所有要导航/收结果的模块 |
| `feature-demo` / `feature-about` | 演示业务模块（页面放这里，验证多模块聚合） | 仅演示 |
| `app` | 演示宿主：Application 初始化 + 聚合注册表 + 场景主页 + **全部自动化用例** | 仅演示 |

### 4.2 KSP 在每个模块生成什么

只要模块里存在 `@Route`，KSP 就在该模块 `build/generated/ksp/...` 下生成（包名 = `ksp.arg("trouter.modulePackage") + ".generated"`）：

| 生成物 | 内容 | 谁消费 |
|---|---|---|
| `GroupLoader_<分组名>` | 一个分组的路由清单（只存目标类**名字符串**，不引用类） | `TRouterGroupRegistry` 聚合 |
| `TRouterGroupRegistry` | 本模块所有分组的加载器清单（每个模块一个） | 宿主 `install(...)` 聚合装配 |
| `CrossProcessPaths` | 本模块 `@CrossProcess` 页面的 path 集合 | 配 `remoteWhitelist` |
| `TRouterTargetInterceptorNames` | 页面类 → `@Interceptor(names)` 映射 | 配 `targetInterceptorResolver` |

> 💡 “只存类名字符串”是刻意的：`init / install` 阶段**不会加载任何页面类**，真加载发生在
> `navigate` 那一刻。所以路由表再大也不影响启动，这也是跨模块安全的根基。

---

## 5. 环境要求

| 项目 | 值 |
|---|---|
| minSdk / targetSdk（compileSdk） | 24 / 37 |
| Java | 11（字节码目标），JDK 17 运行构建 |
| AGP / Gradle | 9.3.2 / 9.5.0（仓库验证环境） |
| Kotlin / KSP | 2.2.10 / 2.2.10-2.0.2 |
| 页面容器 | 目标页可自由用 View 体系或 Compose（demo 两种都有）；`trouter-core` 自身依赖 `androidx.fragment` / `androidx.core` / Timber，会传递给你，无需重复添加 |

> 💡 上面是**本仓库已验证**的构建环境。TRouter 自身约束只有 minSdk 24+；理论上更低版本
> AGP/Kotlin 也可用，但未逐一验证，遇到问题先对照这里的环境。

---

## 6. 快速接入（最小可用）

> 当前 TRouter **尚未发布到中央仓库**，以**源码模块**方式接入：
> 把 `trouter-annotation` / `trouter-processor` / `trouter-core` 三个目录拷进你的工程
> （或作为子模块），按下面接线即可。见[第 18 节](#18-路线图与当前状态诚实声明)。

### 第 1 步：settings.gradle.kts 里 include

```kotlin
include(":trouter-annotation")
include(":trouter-processor")
include(":trouter-core")
```

### 第 2 步：业务模块的 build.gradle.kts

**只要这个模块里有 `@Route` 页面，就按下面配**（application / library 都一样）：

```kotlin
plugins {
    alias(libs.plugins.android.library)  // 或 com.android.application
    alias(libs.plugins.ksp)              // 引入 KSP 插件
}

android {
    namespace = "com.demo.trouter.feature.demo" // 换成你的包名
    // ...
}

// 关键：生成代码的包名 = namespace + ".generated"，两者必须一致
ksp {
    arg("trouter.modulePackage", "com.demo.trouter.feature.demo")
}

dependencies {
    implementation(project(":trouter-annotation"))  // 注解
    implementation(project(":trouter-core"))        // 运行时 API
    ksp(project(":trouter-processor"))              // 处理器
    // 业务自己的依赖照常写
}
```

> 💡 宿主模块（`:app`）即使自己有 `@Route` 页面，配置方式也完全一样，
> 只是它还要负责“聚合装配”（第 4 步）。

### 第 3 步：把路径写进 RouterContract

```kotlin
// 建议放 core 或一个所有模块都能依赖的公共模块；也可以每个模块自建一个（引用自己的）
object RouterContract {
    const val PATH_SECOND: String = "/second"
    const val PATH_ABOUT: String = "/about"
    const val GROUP_SECONDARY: String = "secondary" // 分组名常量，跟 path 一样不许硬编码
}
```

### 第 4 步：标注解 + 写聚合注册表

```kotlin
// SecondActivity.kt —— 页面类只标注解，不写任何“我是路由”的注册代码
@Route(path = RouterContract.PATH_SECOND)
class SecondActivity : ComponentActivity() { /* ... */ }
```

```kotlin
// app 模块：把每个模块生成的 TRouterGroupRegistry 显式聚合成一个注册表
// （新增一个 feature 模块 = 在这里加一行，这是模块清单的“源码可见交汇点”）
object AppRouteRegistry : GroupLoaderRegistry {

    override fun loaders(): List<GroupLoader> =
        com.demo.trouter.generated.TRouterGroupRegistry.loaders() +                      // :app 自己的
            com.demo.trouter.feature.demo.generated.TRouterGroupRegistry.loaders() +     // feature-demo
            com.demo.trouter.feature.about.generated.TRouterGroupRegistry.loaders()      // feature-about
}
```

### 第 5 步：Application 里初始化

```kotlin
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TRouter.init(
            this,
            TRouterConfig(
                isDebug = true,                    // 日志开关（见 13 节）
                interceptors = emptyList(),        // 全局拦截器，先不配（见 8 节）
                onLost = { path -> /* 统计“没找到的路径” */ },
            ),
        )
        TRouter.install(AppRouteRegistry)          // 必须在 init 之后
    }
}
```

> ⚠️ **顺序不能错**：`init` 必须在 `install` 之前。没 `init` 就 `navigate` 会得到
> `TRouterResult.NotInitialized`。

### 第 6 步：跳转

```kotlin
val result = TRouter.navigate(RouterContract.PATH_SECOND)
when (result) {
    is TRouterResult.Success -> {}                       // 打开成功（result.meta 有 path/group/kind）
    is TRouterResult.NotFound -> {}                      // 没这条路（会自动触发 onLost）
    is TRouterResult.Blocked -> {}                       // 被拦截/被终止（不会触发 onLost）
    TRouterResult.NotInitialized -> {}                   // 忘了 init
}
```

到这，最小链路已经通了。下面是每一项能力的完整说明。

---

## 7. 导航

### 7.1 结果语义与兜底回调

所有导航 API 都返回（或回调）密封类 `TRouterResult`，**不可能为 null**，编译器逼你处理失败分支：

| 结果 | 含义 | 会不会触发 `onLost` |
|---|---|---|
| `Success(meta)` | 目标已打开 | 否 |
| `NotFound(path)` | 路由表里没有这条路 | **会** |
| `Blocked(path, reason)` | 被拦截器终止、被拒绝、重定向超跳数、URI scheme 不在白名单等 | 否 |
| `NotInitialized` | 没调用 `TRouter.init` | 否 |

> ⚠️ `NotFound` 与 `Blocked` 的差别是**刻意设计**的：
> “路径不存在”属于**业务预期外的失误**，交给 `onLost` 统一兜底（上报、弹提示）；
> “被拦截器拦下”属于**业务主动决策**，调用方应该自己能看懂 reason，不需要全局兜底。
>
> 另注意：打开目标时抛异常（如目标类损坏）按“打开失败”处理，返回 `NotFound` 并触发 `onLost`。

### 7.2 四种打开方式，什么时候用哪个

| API | 用途 |
|---|---|
| `navigate(path, bundle)` | 最常用。打开 Activity 或 Fragment |
| `navigateForResult(path, requestCode, bundle)` | 要拿到目标页回传的数据（需有前台 Activity） |
| `navigateRemote(path, bundle) { result -> }` | 打开**第二进程**里的页面（见[第 9 节](#9-跨进程-第二进程页面与服务)） |
| `navigateUri(uri, bundle)` | 从 URI / 深链进入（见[第 12 节](#12-深链uri-唤起)） |

### 7.3 参数传递

发参数：导航时带一个 `Bundle`，框架把它原样塞进 Intent extras（Activity）
或 Fragment arguments（Fragment，见 7.4）。

```kotlin
val bundle = Bundle().apply {
    putString("msg", "hello")
    putInt("count", 3)
}
TRouter.navigate(RouterContract.PATH_SECOND, bundle)
```

收参数：页面里用 `RouteArgs`（对齐主流框架 `@Autowired` 的便利，但**不用反射**）：

```kotlin
// Activity 里
val args = RouteArgs.of(intent)
val msg = args.str("msg")
val count = args.int("count", 0)          // 可给默认值，缺 key 不崩
val ok   = args.boolean("switch", false)
```

> 💡 `RouteArgs` 支持 String / Int / Long / Double / Boolean / String 列表 / Serializable / Parcelable；
> 全部委托给 `Bundle` 原生行为，与手写 `get*` 完全等价。

**保留键提醒**：框架打开目标时还会写入路由元数据（path/group/kind/traceId/解析耗时，键前缀
`com.trouter.core.extra.*`）。这些键**以框架为准**，所以业务参数不要用这个前缀命名
（具体键名见 `RouteLaunch`）。

### 7.4 Fragment 目标与页面容器

`@Route` 直接标在 Fragment 子类上即可，例如：

```kotlin
@Route(path = RouterContract.PATH_FRAGMENT_DEMO)
class DemoFragment : Fragment() { /* ... */ }
```

导航到它时，框架自动用 core 内置的 `FragmentContainerActivity` 承载（已在 core 的 manifest
注册，你**不需要**再注册容器页）。容器会把 Intent extras 拷贝成 Fragment arguments，
再把内部键剥掉，所以 Fragment 里这样收：

```kotlin
val args = RouteArgs.of(arguments)   // Fragment 用 arguments，不是 intent
val msg = args.str("msg")
```

### 7.5 带结果回传的导航 · G3

语义与系统 `startActivityForResult` 完全一致，结果仍回你 Activity 的 `onActivityResult`：

```kotlin
// 发起方（页面里）
private val REQ_DETAIL = 1001

fun openDetail() {
    val result = TRouter.navigateForResult(RouterContract.PATH_RESULT_DEMO, REQ_DETAIL)
    // 注意：没有前台 Activity（比如从后台任务触发）时返回 Blocked，reason 会说明
}

// 目标页回传
fun finishWithResult() {
    setResult(Activity.RESULT_OK, Intent().putExtra("result.text", "来自结果页的数据"))
    finish()
}

// 回到发起方
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQ_DETAIL && resultCode == Activity.RESULT_OK) {
        val text = data?.getStringExtra("result.text")
        // ...
    }
}
```

> ⚠️ `navigateForResult` 需要**前台 Activity** 才能用 `startActivityForResult`，
> 无前台时返回 `Blocked`（不会偷偷换用 NEW_TASK，否则结果将无处可回）。

### 7.6 打开语义（任务 / 返回键）

`navigate` 默认**在同一任务内打开**：框架通过生命周期回调记住当前前台 Activity，
优先在它的任务里 `startActivity` —— 返回键能回到调用页，返回栈语义正确。
只有完全没有前台 UI（比如通知栏点击、后台任务）时才回退 `applicationContext + NEW_TASK`。

---

## 8. 拦截器

### 8.1 是什么

拦截器是挂在“**解析到目标之后、真正打开页面之前**”的一串检查/改写逻辑：
登录校验、灰度开关、埋点、权限门禁、把真实页替换成 Mock 页……都可以放这里，
而不是散落在每个页面的 onCreate 里。

每个拦截器最终给出一个**决策**（`InterceptorDecision`，没有 null）：

| 决策 | 效果 |
|---|---|
| `Continue` | 放行，执行下一个拦截器（或最终打开页面） |
| `Block(reason)` | 终止本次导航，返回 `TRouterResult.Blocked(path, reason)`。**不打开页面、不触发 onLost** |
| `Redirect(targetPath)` | 把本次导航改写为另一条 path，**重新走一遍完整导航**（同 traceId，见 13.4） |

> ⚠️ 连续 Redirect / 别名跳转有**上限（3 跳）**，超过会被终止成 `Blocked`，防止“A 重定向到 B、
> B 又重定向回 A”这类死循环。

### 8.2 一个最小拦截器

```kotlin
// 门禁示例：开启后拦掉 /pay，其余放行
class PayGateInterceptor : RouteInterceptor {
    var enabled = true

    override fun intercept(meta: RouteMeta, bundle: Bundle?): InterceptorDecision =
        if (enabled && meta.path == RouterContract.PATH_PAY) {
            InterceptorDecision.Block("支付页维护中，请稍后再试")
        } else {
            InterceptorDecision.Continue
        }
}
```

> 💡 拦截器里能看到 `meta`（path / group / kind / 目标类名）和本次导航的 `bundle`，
> 足够做“按页面”“按参数”“按开关”三种策略。

### 8.3 挂载方式一：初始化时注入（全局链）

```kotlin
TRouter.init(
    this,
    TRouterConfig(
        interceptors = listOf(PayGateInterceptor(), AnotherInterceptor()),
        // ...
    ),
)
```

**顺序即执行顺序**。想全局调优先级，实现 `RouteChainMember.priority`（默认 0）：
同一批全局链内**数值大的先执行**，同数值保持声明顺序（稳定排序）。

> ⚠️ `config.interceptors` 是“初始名单”，`init` 时被拷进运行时注册表；
> 之后要加拦截器用 8.6 的运行时 API，**不用再调 init**。

### 8.4 两种拦截器形态

| 形态 | 接口 | 特点 | 适合 |
|---|---|---|---|
| 原子型 | `RouteInterceptor` | 只看一眼，给个决策，**碰不到**“后面的链” | 门禁、灰度、Mock |
| 洋葱包裹型 | `WrappingInterceptor` | 能包住“后面的链 + 打开页面”，做前后置 | 埋点计时、审计、loading 包裹 |

### 8.5 core 内置 Mock 拦截器

“把真实页换成 Mock 页”太常见，core 直接给了 `MockInterceptor`：

```kotlin
// isDebug 演示期：/second → /mock/second，生产不注入这个拦截器即可
val mock = MockInterceptor(
    redirects = mapOf(RouterContract.PATH_SECOND to RouterContract.PATH_MOCK_SECOND),
)
mock.enabled = true   // 行为开关；关闭 = 一律放行（不受 isDebug 影响）
```

### 8.6 挂载方式二：运行时增删（L2）

```kotlin
TRouter.addInterceptor(interceptor)      // 成功返回 true；同一实例重复注册返回 false
TRouter.removeInterceptor(interceptor)   // 成功返回 true
TRouter.registeredInterceptors()         // 当前全局链只读快照
```

> 💡 **快照语义**：增删只影响**下一次** `navigate`，正在执行中的链不受打扰——
> 不会出现“点下去的瞬间被改链”的竞态。

### 8.7 洋葱包裹拦截器示例

```kotlin
// 计时埋点：包住“放行 + 打开页面”的全过程
class TimingInterceptor : WrappingInterceptor {
    override fun intercept(chain: InterceptorChain): ChainOutcome {
        val start = SystemClock.elapsedRealtime()
        val outcome = chain.proceed()   // 放行：后续拦截器 → 最终打开页面
        log("${chain.meta.path} 全链耗时 ${SystemClock.elapsedRealtime() - start}ms")
        return outcome
    }
}
```

> ⚠️ 同一个 `chain.proceed()` **只能调用一次**：重复调用会抛
> `IllegalStateException`（防“开两次页面”这类缺陷）。不调 proceed 直接返回
> `ChainOutcome.Blocked` / `Redirected` 就是拦截语义。

### 8.8 目标级拦截器 · L3

给**某个页面专属**挂拦截器，用注解声明“要什么”，运行时再绑定“给什么”：

```kotlin
// 1) 页面上声明：本页需要名为 login 的拦截器
@Route(path = RouterContract.PATH_ORDER)
@Interceptor(names = ["login"])
class OrderActivity : ComponentActivity()

// 2) 初始化时：把“目标类 → 需要的名字”的解析函数交给框架
//    （KSP 生成的 TRouterTargetInterceptorNames.namesOf 正好就是这份映射）
TRouter.init(
    this,
    TRouterConfig(
        targetInterceptorResolver = { cls -> TRouterTargetInterceptorNames.namesOf(cls) },
        // ...
    ),
)

// 3) 绑定具体实现（name 重复绑定会被拒绝，先 unbind）
TRouter.bindTargetInterceptor("login", LoginCheckInterceptor())
```

执行位置：**全局链之后、打开页面之前**，同一批快照内同步推进。
如果页面声明了名字、却没绑定，导航会得到 `Blocked`（reason 说明缺了哪个名字）——不会默默放行。

> 💡 `TRouterTargetInterceptorNames` 按模块各自生成一份（只含本模块里标了 `@Interceptor` 的类）。
> 多模块工程里 resolver 要把各模块的映射**合并查询**，写法见[第 15 节的完整例子](#15-一个完整接入例子)。

### 8.9 拦截器行为边界（重要）

- 拦截器里抛异常 = **该次导航按 Blocked 处理**（reason 带异常信息）：不打开页面、不触发 onLost、不崩溃；
- 拦截链是**同步推进**的（一次 navigate 一条调用栈，无并发交错）；
- `isDebug=false` 只关日志，**拦截行为照常生效**。

---

## 9. 跨进程 · 第二进程页面与服务

### 9.1 适用场景

页面需要跑在**独立进程**（独立崩溃隔离、音视频/下载等重活、或系统能力要求单独进程）时，
才需要用到本节。TRouter 的跨进程做法：

> host 进程把导航请求经 **AIDL** 发给第二进程，**由第二进程自己的 TRouter 实例**解析路由表、
> 打开页面，再把结果异步回传。也就是说：跨进程导航 = “第二进程里也有一份路由表 + 一份 TRouter”。

### 9.2 三步配置

**① 目标页与 AIDL 服务都声明到第二进程（app 的 manifest）**：

```xml
<!-- 第二进程页面：注意 android:process=":remote" -->
<activity
    android:name=".RemoteSecondActivity"
    android:exported="false"
    android:process=":remote" />

<!-- AIDL 服务由 core 提供（com.trouter.core.internal.RemoteRouterService），宿主只需声明进程 -->
<service
    android:name="com.trouter.core.internal.RemoteRouterService"
    android:exported="false"
    android:process=":remote" />
```

**② 页面标 `@CrossProcess`**（必须与 `@Route` 同标，处理器强制）：

```kotlin
@Route(path = RouterContract.PATH_REMOTE_SECOND)
@CrossProcess
class RemoteSecondActivity : ComponentActivity()
```

**③ 配置里指明白名单**（KSP 生成的 `CrossProcessPaths.paths` 就是“本模块所有 @CrossProcess 页面”）：

```kotlin
TRouterConfig(
    remoteService = ComponentName(this, RemoteRouterService::class.java),
    remoteWhitelist = CrossProcessPaths.paths,   // null = 不校验（不建议生产这么做）
)
```

### 9.3 发起导航

```kotlin
TRouter.navigateRemote(
    RouterContract.PATH_REMOTE_SECOND,
    Bundle().apply { putString("msg", "hello") },   // 基础类型参数可跨进程
) { result ->
    // 回调在主线程；result 是统一 TRouterResult，处理方式和 navigate 完全一样
    when (result) {
        is TRouterResult.Success -> {}
        is TRouterResult.Blocked -> {}   // 白名单拒绝 / 服务未配置 / 远端失败 / 超时……
        is TRouterResult.NotFound -> {}
        TRouterResult.NotInitialized -> {}
    }
}
```

> ⚠️ **每个进程都要各自 `init` + `install`**：Application 的 onCreate 在 :remote 进程也会执行，
> 所以初始化代码天然两份——这正是“远端用自己路由表解析”的前提。
> 白名单只约束 `navigateRemote` 走不走第二进程；没标 `@CrossProcess` 的 path 会被通道拒绝
> （`Blocked`，reason 会说明），不会偷偷降级成 host 进程打开。

### 9.4 跨进程参数类型限制（重要）

参数本质是过 AIDL 的 Bundle：

- ✅ String / 基本类型 / Serializable；
- ✅ 自定义 Parcelable：**仅当两端在同一 App、共享同一个类**时可行（同 App 双进程没问题）；
- ❌ 不要把 Activity / 非 Parcelable 对象塞进跨进程 bundle。

### 9.5 跨进程服务调用 · G2-remote

不只页面可以跨进程，**服务调用**也可以：

```kotlin
// ① 第二进程侧（远端 Application 里，只注册一次）：
if (Process.myProcessName().endsWith(":remote")) {
    TRouter.registerRemoteEndpoint("demoClock") { args ->
        "clock pid=${Process.myPid()} q=${args?.getString("q")}"
    }
}

// ② host 侧异步调用：
TRouter.callRemoteService("demoClock", bundleOf("q" to "hi")) { reply ->
    if (TRouter.isRemoteEndpointError(reply)) {
        // 返回串以 "-ERR " 开头 = 未注册 / 远端异常
    } else {
        // 正常结果字符串，主线程回调
    }
}
```

> 💡 端点签名统一是 `(Bundle) -> String`，结构化结果请自己在字符串里编码（演示的
> `RemoteReplyCodec` 有完整示例）。端点按进程独立注册：同名重复注册会被拒绝。

---

## 10. 动态路由与路由表热更

### 10.1 是什么

静态路由（`@Route`）编译期就定死了。但有些路由是**运行时才知道**的：
服务端下发的新页面、插件化功能开关、A/B 实验入口。这时用 `registerRoute`：

```kotlin
TRouter.registerRoute(
    RouteMeta(
        path = RouterContract.PATH_DYNAMIC_DEMO,
        group = RouterContract.GROUP_DYNAMIC,
        targetClassName = "com.demo.trouter.DynamicDemoActivity",  // 注意：全限定名字符串
        kind = RouteTargetKind.ACTIVITY,                          // 或 FRAGMENT
    ),
)
```

动态路由与静态路由**同表共存**：拦截器、降级、图谱、快照全部自动生效，无需额外接线。

> ⚠️ **动态路由页面也必须注册进 AndroidManifest**。路由表只是“知道有这个路径”，
> 真正 `startActivity` 时系统仍要求 Activity 已声明。类名写错/类不存在时，
> 用 [checkRouteTargets](#107-路由目标体检-g7) 能在运行期查出来。

### 10.2 注销

```kotlin
TRouter.unregisterRoute(path)   // 静态/动态路由都能注销（路径级统一语义）
```

### 10.3 原子热更（推荐入口）

要同时“撤一批、上一批”，用 `applyRouteConfig`——**要么整批成功，要么零变更**：

```kotlin
val ok = TRouter.applyRouteConfig(
    removes = listOf("/old/a"),               // 先移除
    adds = listOf(metaA, metaB),              // 再注册
)
// 校验失败（与现存路径重复、字段非法）→ 返回 false 且什么都没改
```

配套持久化：

```kotlin
TRouter.saveDynamicRoutes(file)     // 导出当前【动态】路由（不含静态）
TRouter.loadDynamicRoutes(file)     // 读文件，先清当前动态集、再原子导入
```

### 10.4 路由表 JSON · G6

```kotlin
val json = TRouter.exportRouteMapJson()   // 全部路由（静态+动态）规范 JSON，供下发/审计

val ok = TRouter.importRouteMapJson(json) // 作为“动态覆盖层”导入：与静态路由冲突或格式非法
                                          // → 整批失败、零变更
```

> 💡 覆盖层语义：导入内容永远**压不过**静态路由（冲突即失败），静态表是底座，动态表是叠加。

### 10.5 路由别名 · G5

给路径起别名，或把“一类路径”收编到同一目标：

```kotlin
TRouter.registerRouteAlias("/old", RouterContract.PATH_SECOND)     // 精确别名
TRouter.registerRouteAlias("regex:/user/\\d+", RouterContract.PATH_ABOUT) // 正则别名（注册序首个命中）

TRouter.unregisterRouteAlias(alias)
TRouter.registeredRouteAliases()   // 只读快照
```

> ⚠️ 别名**不能与已注册路由重名**（静态优先，重名注册会被拒绝，返回 false）。
> 只有“路由表查不到”的 path 才会尝试走别名。

### 10.6 路由图谱 · V5

```kotlin
val graph = TRouter.routeGraph()
graph.nodes   // 目标类全名（去重、按首次出现顺序）
graph.edges   // 每条注册路由一条边（path/group/kind/目标类）
```

图由当前路由表（静态+动态）实时导出，顺序确定（注册序）——可用于运维大盘、测试断言。

### 10.7 路由目标体检 · G7

```kotlin
val bad = TRouter.checkRouteTargets()   // 返回“类加载不了”的路由清单（不含正常的）
// 静态路由的类由 KSP 保证存在；这个 API 主要抓“动态注册传错类名”这类迟发问题
```

---

## 11. 进程内服务层

页面导航之外，模块间还有一种更轻的协作：**接口 → 实现**注册。

```kotlin
// 定义接口（放公共模块）
interface ILoginApi { fun token(): String }

// 实现方注册（App 启动或模块初始化时）
class LoginApiImpl : ILoginApi { override fun token() = "abc" }
TRouter.registerService(ILoginApi::class.java, LoginApiImpl())   // 重复注册同一接口返回 false

// 使用方按接口取，不 import 实现类
val api = TRouter.findService(ILoginApi::class.java)             // 没注册时返回 null，自行判空
```

配套：`unregisterService(接口)` 注销、`registeredServices()` 快照。

> 💡 典型用法：让“业务能力提供方”和“页面使用方”只通过接口耦合，路由负责页面跳转，
> 服务层负责能力注入，两者互不干扰。跨进程版本见 [9.5](#95-跨进程服务调用-g2-remote)。

---

## 12. 深链（URI 唤起）

```kotlin
TRouterConfig(deeplinkSchemes = setOf("myapp"))   // 允许进入路由的 scheme 白名单

// 约定：scheme://host/path?k=v   →  path 段即内部路由 path，query 并入导航参数
val uri = Uri.parse("myapp://app/second?msg=hi&count=3")
val result = TRouter.navigateUri(uri)
```

- scheme 不在白名单 → `Blocked`（reason 会点名缺哪个 scheme）；
- query 参数并入导航 Bundle，且 **query 优先于**入参 bundle 的同名 key。

> ⚠️ `navigateUri` 只是**路由层入口**。外部点击链接/扫码唤起，还需要你自己在
> Launcher Activity 上配 `<intent-filter>`，并在 `onCreate` 里把 `intent.data` 交给
> `TRouter.navigateUri`（框架不替你解析外部 Intent）。

---

## 13. 可观测性

### 13.1 页面级证据（RouteLaunch）

TRouter 打开页面时，会把本次路由元数据写进 Intent extras / Fragment arguments。
页面用一行代码自述“我是被 TRouter 打开的，还是被直连/系统启动的”：

```kotlin
val runtime = RouteLaunch.describe(intent)   // Activity；Fragment 传 arguments
// → "✓ 本次由 TRouter 打开 · path=/second · kind=ACTIVITY · group=default · traceId=xxxx · 解析 3ms"
// 直连/系统启动（没带元数据）→ "（直连/系统启动：本页未带 TRouter 路由元数据）"
```

> 💡 建议每个页面都显示这一行：真机上“这页到底走没走路由”一目了然，排障神器。

### 13.2 结构化日志（Timber）

所有日志走 **Timber**，Tag=`TRouter`，消息是结构化 `[节点]` 行：

```bash
adb logcat -s TRouter
```

主要节点：`[navigate][entry/exit]`、`[GroupLoader][load][start/end]`、`[interceptor][start/eval/end]`、
`[route][register/alias/apply/...]`、`[service][...]`、`[remote][send/recv/fail]`
（跨进程**服务调用**的节点是 `[remote][service][send/recv/fail]`）。

- `isDebug=true` 才输出；`false` 全静默（但拦截/降级等**行为照常**）；
- 想接自己的日志体系：`TRouterConfig(logSink = { msg -> ... })`，收到的是与 logcat 一致的完整行；
- **traceId**：同一次用户点击，从入口到出口、到 Redirect 各跳、到跨进程 send/recv，都带同一个
  traceId —— 一条导航一条链，看日志能完整跟下来龙去脉。

### 13.3 运行时快照查询

```kotlin
TRouter.registeredRoutes()          // 当前全部路由（静态+动态），注册序
TRouter.registeredInterceptors()    // 当前全局拦截器
TRouter.registeredTargetInterceptors() // 已绑定的目标级拦截器（name → 实例）
TRouter.registeredRouteAliases()    // 已注册别名
TRouter.registeredServices()        // 已注册服务接口
```

---

## 14. 编译期保障

| 防线 | 手段 | 级别 |
|---|---|---|
| 同模块重复 path | KSP 直接**编译报错**（同一 path 出现在两个类上） | 编译期 |
| path 写字面量 | Warning：“请引用 RouterContract 常量” | 编译期（告警） |
| @Route 标错类型 | 目标类不是 Activity/Fragment 子类 → 编译报错 | 编译期 |
| @CrossProcess / @Interceptor 没配 @Route | KSP ERROR | 编译期 |
| 跨模块 path 冲突 | 演示工程在 :app 接了 `verifyCrossModuleRouteConflicts`（debug 构建扫各模块生成目录，冲突使构建失败）。接入方可照抄该接线 | 构建期（可选） |
| install 时撞车（重复 install 等） | first-wins 保留先注册者 + 日志告警 | 运行时防线 |
| 动态注册冲突 | 注册被拒绝（返回 false）/ `applyRouteConfig` 整批失败 | 运行时防线 |

> 路径常量与“编译期冲突即失败”组合，让**改路径=全工程通知**，从源头消灭“字符串拼错、
> 两个模块抢同一个 path”这两类最常见的路由事故。

---

## 15. 一个完整接入例子

把上面串起来：假设公司要做电商 App，两个业务模块 `feature-order`、`feature-user` + 宿主 `app`。

```kotlin
// ---------- feature-user / UserProfileActivity.kt ----------
@Route(path = RouterContract.PATH_USER)
@Interceptor(names = ["login"])          // 这个页面必须过 login 拦截器
class UserProfileActivity : ComponentActivity() {
    // onCreate 里用 RouteLaunch.describe(intent) 显示“由 TRouter 打开”证据
}
```

```kotlin
// ---------- 宿主 app / 聚合注册表 ----------
// 每个模块的 KSP 生成包名 = 该模块 namespace + ".generated"
object AppRouteRegistry : GroupLoaderRegistry {
    override fun loaders(): List<GroupLoader> =
        com.myapp.generated.TRouterGroupRegistry.loaders() +
            com.myapp.feature.order.generated.TRouterGroupRegistry.loaders() +
            com.myapp.feature.user.generated.TRouterGroupRegistry.loaders()
}
```

```kotlin
// ---------- 宿主 app / MyApplication ----------
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val config = TRouterConfig(
            isDebug = BuildConfig.DEBUG,
            interceptors = emptyList(), // 全局链暂无；本例只用目标级 login 拦截
            // 目标级拦截器映射按模块各自生成，这里把所有模块都查一遍再合并
            targetInterceptorResolver = { cls ->
                com.myapp.generated.TRouterTargetInterceptorNames.namesOf(cls) +
                    com.myapp.feature.user.generated.TRouterTargetInterceptorNames.namesOf(cls)
            },
            deeplinkSchemes = setOf("myapp"),
            onLost = { path -> reportMissingRoute(path) },
        )
        TRouter.init(this, config)
        TRouter.install(AppRouteRegistry)

        // 给 @Interceptor(names=["login"]) 绑定实现（本工程只有一个页面声明了 login）
        TRouter.bindTargetInterceptor("login", LoginInterceptor())
    }
}
```

```kotlin
// ---------- feature-order / 下单入口 ----------
fun openUserCenter(activity: Activity) {
    val bundle = Bundle().apply { putString("from", "order") }
    val result = TRouter.navigate(RouterContract.PATH_USER, bundle)
    if (result is TRouterResult.Blocked) {
        Toast.makeText(activity, result.reason, Toast.LENGTH_SHORT).show()  // 没登录 → 引导登录
    }
}
```

`feature-order` **没有 import 任何 `feature-user` 的类**，只知道
`RouterContract.PATH_USER` 这个常量 —— 组件化解耦完成。

---

## 16. 常见问题（FAQ）

**Q1：点击后返回 NotFound，页面没打开？**
依次查：① path 是否真的写进 `RouterContract` 并被 `@Route` 引用；② 目标所在模块是否已
`install` 进注册表（新增模块最容易漏聚合那一行）；③ `init` 是否在 `install` 之前；
④ 看日志 `[route][verify][missing]` / `[navigate]` 节点。NotFound 会自动触发 `onLost`，
可以在那里打点确认。

**Q2：页面打开了，但自述行显示“直连/系统启动”？**
说明该页没带 TRouter 元数据 —— 被别的入口（deep link Activity、测试直启）打开过。
用 `RouteLaunch.describe` 区分即可，不算 bug。

**Q3：导航到 Fragment 需要自己建容器页吗？**
不需要。core 的 `FragmentContainerActivity` 已在 core manifest 注册，自动承载。
Fragment 里用 `RouteArgs.of(arguments)` 收参。

**Q4：动态注册了路由还是 NotFound？**
① `targetClassName` 是否写对（全限定名）且类可加载——用 `checkRouteTargets()` 排查；
② 该 Activity **是否在 manifest 注册**（动态路由不豁免 manifest 注册）。

**Q5：navigateRemote 返回 Blocked？**
看 reason：白名单拒绝 → 目标页漏标 `@CrossProcess` 或 whitelist 没配全；
服务未配置/连不上 → 检查 `remoteService`、`:remote` 进程的 Application 是否也做了
`init + install`（远端进程没有路由表，自然解析不了）。

**Q6：加了拦截器但没生效？**
① 确认是 `config.interceptors` 注入或 `addInterceptor` 加的**同一个实例**（有状态拦截器最常
犯“又 new 了一个”）；② 增删只影响**下一次** navigate；③ 日志里看 `[interceptor][start]` 链长。

**Q7：Blocked 和 NotFound 到底啥区别？**
Blocked = 业务主动拦截/拒绝（不触发 onLost）；NotFound = 没这条路或打开失败（触发 onLost）。
不要把“拦截”当成“没找到”处理，否则 onLost 会被业务拦截刷屏。

**Q8：跨进程参数带不过去？**
过 AIDL 的 Bundle 只支持基础类型 / String / Serializable；自定义 Parcelable 仅同 App
两端共享类时可用。别的类型先序列化成 String。

**Q9：重复注册同一 path 会怎样？**
编译期同模块重复直接报错；动态/热更重复 → 注册被拒（false / 整批失败）；
`install` 阶段撞车（如重复 install）→ first-wins + 日志。设计上不允许“悄悄覆盖”。

**Q10：忘了 init 直接 navigate？**
返回 `NotInitialized`，不会崩、不会空指针 —— 这就是密封结果的用处，编译器会逼你写这个分支。

---

## 17. 演示工程怎么跑

`app` 是**自描述验收测试台**：不用读代码，点一遍主页的 S01–S22 就能看懂每个能力。

```bash
# 1) 起模拟器（或连真机）
$ANDROID_HOME/emulator/emulator -avd <你的AVD> &

# 2) 安装
./gradlew :app:installDebug

# 3) 打开 App：主页是可滚动的场景列表，底部有“路由表快照”和“图谱摘要”
```

| 场景 | 验证什么 | 对应用例类（摘录） |
|---|---|---|
| S01/S02/S03 | 基础跳转 / Fragment 承载 / 分组路由 | `MainRouterTest` |
| S04 | 未注册路径降级（onLost） | `MainRouterTest` |
| S08/S09 | 门禁 Block / Mock Redirect 开关 | `TRouterInterceptorUiTest` |
| S10 | 拦截器契约（顺序/短路/跳数上限/异常=Block） | `TRouterInterceptorContractTest` |
| S11 | 跨进程导航（页面显示 pid，与 host 不同即证明双进程） | `TRouterRemoteContractTest` |
| S12 | 跨进程契约（白名单/远端 NotFound/超时） | `TRouterRemoteContractTest` |
| S13/S14 | 动态注册/注销 + 图谱 | `TRouterDynamicContractTest` |
| S19–S21 | 参数透传（Activity / Fragment / 跨进程） | `TRouterParamPassingTest` |
| S22 | navigateForResult 结果回传 | `TRouterResultAndArgsTest` |

自动化用例共 **70 个 @Test**（分布在 16 个 Instrumentation 测试类，覆盖导航/拦截器/跨进程/
动态/热更/深链/别名/服务/参数/结果等契约），日志全绿后各里程碑还有独立测试报告（见 19）。

> 详细导览（每个场景怎么点、期望什么、对应用例名）见 `docs/demo/README.md`。

---

## 18. 路线图与当前状态（诚实声明）

### 已实现并经过测试验证

V1 导航（Activity/Fragment、结果与降级）→ V2 拦截器 → V3 多模块聚合 → V4 跨进程
（AIDL + `@CrossProcess` 白名单 + 跨进程服务）→ V5 动态路由与图谱；
以及对齐开源 Router 的差距收敛项：**G1 深链 / G2 服务层（含跨进程）/ G3 结果回传 /
G4 收参助手 / G5 路由别名 / G6 路由表 JSON / G7 目标体检 / G8 类缓存 / G11 拦截器优先级**。
核心链路（编译 → 聚合 → 拦截 → 打开 → 可观测 → 双进程验证）已可用，**达到可集成使用的状态**。

### 尚未实现（本文不写用法，避免误导）

- **G9 模块自动初始化**（类似 FlowTask 的模块生命周期编排）；
- **G10 Action 全局事件**（页面间轻量事件总线）。

这两项在 `docs/我们与开源Router差距分析.md` 里被列为主要差距，计划后续补齐；
补齐后本文会同步更新。

### 发布状态

- 尚未发布到 Maven 中央仓库，接入 = 源码模块引入（见[第 6 节](#6-快速接入最小可用)）；
- 计划中的中文/英文双语文档：中文版即本文；英文版待本文定稿后单独发布。

---

## 19. 文档索引

| 文档 | 内容 |
|---|---|
| `docs/demo/README.md` | 演示台逐场景导览（怎么点、期望什么、对应用例） |
| `docs/V1.0-工程搭建方案.md` ~ `docs/V5.0-动态路由与图谱方案.md` | 各里程碑设计方案 |
| `docs/Backlog补全方案-动态化与硬化.md` | 动态化/硬化/差距收敛补全方案 |
| `docs/开源Router调研.md` | ARouter / WMRouter / TheRouter / DRouter 调研 |
| `docs/我们与开源Router差距分析.md` | 与本项目的逐项差距分析（G1–G11） |
| `docs/版本回顾与缺口核查.md` | 版本演进回顾与缺口核查 |
| `docs/功能对照核查清单.md` | 功能点对照核查 |
| `docs/reports/*` | 各里程碑与各批次测试报告 |

---

*本文按仓库当前代码撰写；若代码演进导致不一致，以代码与测试为准，并请同步修订本文。*
