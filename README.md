# TRouter：Android 页面跳转框架

> 一句话：**让"打开某个页面"这件事，不再需要模块之间互相引用代码。**

这份文档写给第一次接触本项目的人。全部用大白话写，出现的每个专业词都会当场解释。
如果你只想先用起来，直接跳到 [5 分钟接入](#3-5-分钟接入)。

---

## 目录

1. [它能帮你解决什么麻烦](#1-它能帮你解决什么麻烦)
2. [看一眼代码就懂](#2-看一眼代码就懂)
3. [5 分钟接入](#3-5-分钟接入)
4. [我想做 X，该用哪个方法](#4-我想做-x该用哪个方法)
5. [常用功能与写法](#5-常用功能与写法)
6. [三道自动护栏（写错了，编译就报错）](#6-三道自动护栏写错了编译就报错)
7. [出问题了怎么查](#7-出问题了怎么查)
8. [演示工程：点一遍就懂了](#8-演示工程点一遍就懂了)
9. [现在有什么、还没做什么](#9-现在有什么还没做什么)
10. [名词小词典](#10-名词小词典)
11. [更多文档](#11-更多文档)

---

## 1. 它能帮你解决什么麻烦

组件化（把 App 拆成多个模块）之后，团队几乎都会撞上这三件事：

**麻烦一：模块之间互相引用，拆了等于没拆。**
A 模块想打开 B 模块的页面，就得 `import` B 的类，于是 A 依赖 B、B 又依赖 A，最后谁也不敢删代码。
TRouter 的做法：**页面之间只认一个字符串路径**（比如 `/second`），A 完全不需要知道 B 的类名。

**麻烦二：跳转前的检查到处复制。**
"没登录就先去登录页""这个功能没灰度到就不让进""线上出问题临时把页面换成兜底页"——
这些逻辑如果写在每个页面的 `onCreate` 里，就会散落几十处，改一次要改一天。
TRouter 的做法：把这些检查写成**拦截器**，在"要打开页面之前"统一跑一遍。

**麻烦三：特殊的页面没法用普通方式打开。**
比如页面必须跑在**独立进程**里（音视频、下载这类重活），或者页面是服务端下发的、编译时还不存在。
TRouter 的做法：这两种都提供现成入口，调用方式和普通跳转一样。

---

## 2. 看一眼代码就懂

**第一步，给页面贴个标签（一行注解）：**

```kotlin
@Route(path = "/second")            // 这个页面从此可以通过 "/second" 打开
class SecondActivity : ComponentActivity()
```

**第二步，App 启动时装配一次（Application 里写一次）：**

```kotlin
TRouter.init(this, TRouterConfig())
TRouter.install(AppRouteRegistry)   // 把各模块生成的"页面清单"汇总起来
```

**第三步，任何地方都能打开它（不需要 import 那个页面类）：**

```kotlin
when (val result = TRouter.navigate("/second")) {
    is TRouterResult.Success -> {}      // 打开成功
    is TRouterResult.NotFound -> {}     // 没有这个路径
    is TRouterResult.Blocked -> {}      // 被拦截器拦下了
    TRouterResult.NotInitialized -> {}  // 忘了 init
}
```

注意最后那段 `when`：**这个方法不会返回 null**，编译时会逼你把失败的情况也处理掉。
这是这个库的一个基本态度——**出了问题要看得见，而不是悄悄什么都不做**。

---

## 3. 5 分钟接入

> 目前还没发布到 Maven 中央仓库，所以接入方式是**把源码模块拷进你的工程**（下面第 1 步）。
> 以后发版了这一段会改。

### 第 1 步：把 3 个模块拷进工程，并在 `settings.gradle.kts` 里登记

```kotlin
include(":trouter-annotation")   // 注解
include(":trouter-processor")    // 编译期代码生成器
include(":trouter-core")         // 运行时（真正干活的）
```

另外两个是"可选的检查工具"，想用再加（见[第 6 节](#6-三道自动护栏写错了编译就报错)）：
`trouter-lint`、`trouter-gradle-plugin`。

### 第 2 步：给"有页面的模块"配好代码生成

```kotlin
plugins {
    alias(libs.plugins.android.library)   // 或 com.android.application
    alias(libs.plugins.ksp)               // 负责在编译期生成代码
}

android {
    namespace = "com.demo.feature.user"
}

ksp {
    // 生成代码的包名 = 本模块的 namespace + ".generated"，两边必须一致
    arg("trouter.modulePackage", "com.demo.feature.user")
}

dependencies {
    implementation(project(":trouter-annotation"))
    implementation(project(":trouter-core"))
    ksp(project(":trouter-processor"))
}
```

### 第 3 步：把路径写成常量（不要到处写字符串）

```kotlin
object RouterContract {
    const val PATH_SECOND: String = "/second"
}
```

页面里引用它，而不是写 `"/second"`：

```kotlin
@Route(path = RouterContract.PATH_SECOND)
class SecondActivity : ComponentActivity()
```

> 💡 为什么坚持这样？路径写在十个地方，改路径时就会漏掉八个。
> 写成常量后，改一处、全工程跟着变，编译器还会帮你找漏改的地方。

### 第 4 步：把各模块的页面清单汇总起来

每个有页面的模块，编译时都会生成一份自己的"页面清单"。主模块要把它们合起来：

```kotlin
object AppRouteRegistry : GroupLoaderRegistry {
    override fun loaders(): List<GroupLoader> =
        com.myapp.generated.TRouterGroupRegistry.loaders() +            // 主模块自己的
            com.myapp.feature.user.generated.TRouterGroupRegistry.loaders() +
            com.myapp.feature.order.generated.TRouterGroupRegistry.loaders()
}
```

> 💡 新加一个业务模块，就在这里加一行。这是"模块清单"唯一的汇总点，一眼能看全。

### 第 5 步：App 启动时初始化

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TRouter.init(this, TRouterConfig(isDebug = BuildConfig.DEBUG))
        TRouter.install(AppRouteRegistry)
    }
}
```

> ⚠️ 顺序不能反：`init` 必须在前。忘了 `init` 直接跳转，会得到 `NotInitialized`（不会崩，但页面打不开）。

到这里就能用了。下面按"我想做什么"来查。

---

## 4. 我想做 X，该用哪个方法

| 我想…… | 用这个 | 详细说明 |
|---|---|---|
| 打开一个页面 | `TRouter.navigate(路径, 参数)` | [5.1](#51-打开一个页面) |
| 跳转时带参数 / 在页面里取参数 | `navigate(路径, bundle)` + `RouteArgs` | [5.2](#52-带参数跳转与取参数) |
| 打开页面，并在它返回时拿到数据 | `TRouter.navigateForResult(...)` | [5.3](#53-打开页面并拿到返回值) |
| 跳转前做登录校验 / 灰度 / 临时换页 | 拦截器（`RouteInterceptor`） | [5.4](#54-拦截器跳转前先做检查) |
| 跳转前要联网、要等待（不能卡主线程） | `TRouter.navigateAsync(...)` | [5.5](#55-跳转前需要等待异步拦截器) |
| 页面必须跑在另一个进程里 | `@CrossProcess` + `TRouter.navigateRemote(...)` | [5.6](#56-把页面开在另一个进程里) |
| 运行时才注册页面（服务端下发） | `TRouter.registerRoute(...)` | [5.7](#57-运行时才注册的页面) |
| 服务端下发一整份路由表 | `importRouteMapJson` / `exportRouteMapJson` | [5.8](#58-导出导入整份路径表) |
| 老路径改名，但旧链接还要能用 | `TRouter.registerRouteAlias(...)` | [5.9](#59-路径别名) |
| 模块之间用接口调用，不用互相 import | `TRouter.registerService / findService` | [5.10](#510-模块之间用接口调用) |
| 跨进程传一个业务对象 | `@RemotePojo` | [5.11](#511-跨进程传业务对象) |
| 跨进程调接口（像调本地方法一样） | `@RemoteApi` + `TRouter.remoteApi(...)` | [5.12](#512-跨进程调接口) |
| 外部链接/扫码进来打开页面 | `navigateUri` + 配置 scheme 白名单 | [5.13](#513-从外部链接进来) |
| 检查有没有"注册了但页面不存在"的路径 | `TRouter.checkRouteTargets()` | [5.14](#514-自检注册了但打不开的路径) |

---

## 5. 常用功能与写法

### 5.1 打开一个页面

```kotlin
val result = TRouter.navigate(RouterContract.PATH_SECOND)
```

`result` 有四种情况，含义如下（**这四种要分清楚，排查问题时全靠它**）：

| 返回结果 | 什么意思 | 会不会触发你配置的 `onLost`（兜底回调） |
|---|---|---|
| `Success` | 页面已经打开了 | 不会 |
| `NotFound` | 没有这条路径（可能忘了注册，也可能路径写错了） | **会** |
| `Blocked` | 被拦截器拦住了，或调用方式不对（比如用同步方式跳一个需要等待的页面） | 不会 |
| `NotInitialized` | 忘了调用 `TRouter.init` | 不会 |

> 💡 为什么"路径不存在"和"被拦住"要分开？
> 前者是**出错**（应该上报、应该有人修）；后者是**业务主动决定**（比如"没登录，不让你进"），
> 调用方自己就能处理，不需要惊动全局兜底。

**页面片段（Fragment）也能直接跳**——贴同样的注解，TRouter 会自动用内置的容器页面把它装起来，
你不需要自己写容器：

```kotlin
@Route(path = RouterContract.PATH_FRAGMENT_DEMO)
class DemoFragment : Fragment()
```

> ⚠️ 不管哪种页面，都必须在 `AndroidManifest.xml` 里注册过。路由框架只负责"知道有这个路径"，
> 真正打开页面还是系统说了算。

### 5.2 带参数跳转与取参数

发参数：

```kotlin
val bundle = Bundle().apply {
    putString("msg", "你好")
    putInt("count", 3)
}
TRouter.navigate(RouterContract.PATH_SECOND, bundle)
```

取参数（页面里）：

```kotlin
val args = RouteArgs.of(intent)     // Fragment 里写 RouteArgs.of(arguments)
val msg = args.str("msg")           // 取不到就给 null，不会崩
val count = args.int("count", 0)    // 也可以给默认值
```

> ⚠️ 框架自己也会往参数里写一些东西（比如这次跳转的路径、耗时，用来做页面上的证据显示）。
> 它们的键名以 `com.trouter.core.extra.` 开头，**你的业务参数不要用这个前缀**，否则会被覆盖。

### 5.3 打开页面并拿到返回值

和系统自带的方式一样，结果还是回到你的 `onActivityResult`：

```kotlin
// 发起方
TRouter.navigateForResult(RouterContract.PATH_RESULT_DEMO, REQUEST_CODE)

// 目标页要返回时
setResult(Activity.RESULT_OK, Intent().putExtra("result.text", "给调用方的数据"))
finish()

// 回到发起方
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { ... }
```

> ⚠️ 这种方式要求**当前有前台页面**。如果是在后台（比如通知栏点击）发起，会返回 `Blocked` 并说明原因——
> 因为系统要求"谁来发起的、结果就回到谁那里"，后台没有这个"谁"。

### 5.4 拦截器：跳转前先做检查

拦截器就是"在打开页面之前先跑一段你的逻辑"。它可以做三种决定：

| 决定 | 效果 |
|---|---|
| 放行 | 继续往下走，最终打开页面 |
| 拦下 | 不打开页面，返回 `Blocked`（**不会**触发兜底回调，因为这是你主动决定的） |
| 改道 | 换成另一个页面打开（比如把真实页换成兜底页/灰度页） |

写一个最简单的（把某个页面临时拦住）：

```kotlin
class MaintenanceInterceptor : RouteInterceptor {
    override fun intercept(meta: RouteMeta, bundle: Bundle?): InterceptorDecision =
        if (meta.path == "/pay") {
            InterceptorDecision.Block("支付页维护中，请稍后再试")
        } else {
            InterceptorDecision.Continue
        }
}
```

装上去（两种方式，任选）：

```kotlin
// 方式一：启动时写进配置
TRouterConfig(interceptors = listOf(MaintenanceInterceptor()))

// 方式二：运行中随时加/删（只影响"下一次"跳转，不会打断正在进行的跳转）
TRouter.addInterceptor(interceptor)
TRouter.removeInterceptor(interceptor)
```

还想做"跳转前记一笔、跳转后记一笔"这种前后包裹？用 `WrappingInterceptor`：

```kotlin
class TimingInterceptor : WrappingInterceptor {
    override fun intercept(chain: InterceptorChain): ChainOutcome {
        val start = SystemClock.elapsedRealtime()
        val outcome = chain.proceed()          // 放行：后面的拦截器 → 真正打开页面
        Log.d("T", "本次跳转全流程耗时 ${SystemClock.elapsedRealtime() - start}ms")
        return outcome
    }
}
```

**想让某个拦截器先跑？** 给它一个更大的 `priority`（默认 0，数字大的先执行）。

**只想给某一个页面挂拦截器？** 在页面上写注解、再绑定实现：

```kotlin
@Route(path = RouterContract.PATH_ORDER)
@Interceptor(names = ["login"])          // 这个页面需要名为 login 的拦截器
class OrderActivity : ComponentActivity()
```

```kotlin
// 初始化时告诉框架"去哪查：某个页面需要哪些拦截器"（用生成好的映射表）
TRouterConfig(targetInterceptorResolver = { cls -> TRouterTargetInterceptorNames.namesOf(cls) })

// 再把名字绑定到具体实现
TRouter.bindTargetInterceptor("login", LoginInterceptor())
```

> ⚠️ 写了名字却没绑定实现，跳转会直接失败并说明缺了哪个名字——不会"默默放行"。
> 多模块项目里，每个模块会各自生成一份映射表，要把它们**合起来查**（写法见 [README 末尾的完整例子](#完整接入示例)）。

**拦截器里出了异常会怎样？** 这次跳转按"被拦下"处理（页面不打开、不触发兜底回调、不会崩），
日志里会写清楚是哪个拦截器抛的。

### 5.5 跳转前需要等待（异步拦截器）

有些检查真的需要时间：调接口验票、等定位结果。原来的拦截器是"同步"的，遇到这种只能干等，
而干等会卡住主线程（界面卡住不动）——这是要避免的。

所以提供了一个**异步拦截器**：你可以先"离开"，等结果回来再决定放不放行。

```kotlin
class RiskCheckInterceptor : AsyncInterceptor {
    override fun intercept(chain: AsyncChain) {
        http.check(chain.bundle) { passed ->
            if (passed) {
                chain.proceed()                    // 放行（结果回来后继续打开页面）
            } else {
                chain.block("风控未通过")           // 拦下
            }
        }
    }
}
```

用异步方式跳转（结果在主线程回调，**只会回调一次**）：

```kotlin
val request = TRouter.navigateAsync(RouterContract.PATH_SECOND) { result ->
    when (result) {
        is TRouterResult.Success -> {}
        is TRouterResult.Blocked -> {}   // 被拦下，或超时，或已取消
        is TRouterResult.NotFound -> {}
        TRouterResult.NotInitialized -> {}
    }
}

request.cancel()     // 不想去了可以取消；取消后拦截器"迟到"的放行不会真的打开页面
```

规则（都很直白）：

- 一个异步拦截器只能做一次决定（放行 / 拦下 / 改道 三选一），做第二次会直接报错——防止"开两次页面"；
- 超过 `TRouterConfig.asyncInterceptorTimeoutMs`（默认 5 秒）还没做决定，就按"失败"处理，并告诉你超时了；
- 超时之后拦截器再"迟到放行"也不生效；
- **普通 `navigate` 遇到需要等待的异步拦截器，会明确失败并提示你改用 `navigateAsync`**，绝不会卡住界面；
- 如果异步拦截器当场就放行了（比如"开关关着，直接过"），普通 `navigate` 照样能用。

### 5.6 把页面开在另一个进程里

什么时候需要：页面必须跑在独立进程（音视频、下载这类重活，或者要求崩溃互不影响）。

**第一步，在 `AndroidManifest.xml` 里把页面和服务声明到独立进程：**

```xml
<activity
    android:name=".RemoteSecondActivity"
    android:exported="false"
    android:process=":remote" />        <!-- 关键：指定独立进程 -->

<service
    android:name="com.trouter.core.internal.RemoteRouterService"
    android:exported="false"
    android:process=":remote" />
```

**第二步，页面上加个注解（表示"这个页面允许被跨进程打开"）：**

```kotlin
@Route(path = RouterContract.PATH_REMOTE_SECOND)
@CrossProcess
class RemoteSecondActivity : ComponentActivity()
```

**第三步，配置里指明用哪个服务、允许哪些页面跨进程：**

```kotlin
TRouterConfig(
    remoteService = ComponentName(this, RemoteRouterService::class.java),
    remoteWhitelist = CrossProcessPaths.paths,   // 自动生成的白名单，不用手写
)
```

**第四步，跳转：**

```kotlin
TRouter.navigateRemote(RouterContract.PATH_REMOTE_SECOND, bundle) { result ->
    // 结果在主线程回调，和普通跳转一样处理
}
```

**需要第二个、第三个独立进程？** 每个额外进程写一个空的子类，并在配置里起个名字：

```kotlin
class RemoteRouterServiceSecond : RemoteRouterService()   // 空子类，什么都不用写
```

```kotlin
TRouterConfig(
    remoteServices = mapOf("remote2" to ComponentName(this, RemoteRouterServiceSecond::class.java)),
)

// 跳转时指定目标进程
TRouter.navigateRemote(RouterContract.PATH_REMOTE_THIRD, bundle, target = "remote2") { ... }
```

规则（这几条不知道会踩坑）：

- **每个进程都会各自初始化一份 TRouter**（因为 Application 在每个进程都会创建），所以"远端用自己的路径表打开页面"；
- 白名单只约束"跨进程打开"，不影响普通跳转；
- 跨进程传参数只支持基础类型和字符串；**要传业务对象用 [5.11](#511-跨进程传业务对象) 的 `@RemotePojo`**；
- 目标页没标 `@CrossProcess`，跨进程打开会被拒绝并说明原因（不会偷偷改成本进程打开）。

### 5.7 运行时才注册的页面

有些页面编译时不存在（服务端下发的新页面、实验性入口），可以在运行时注册：

```kotlin
TRouter.registerRoute(
    RouteMeta(
        path = "/dynamic-demo",
        group = "dynamic",
        targetClassName = "com.demo.DynamicDemoActivity",   // 注意：写类的完整名字
        kind = RouteTargetKind.ACTIVITY,
    ),
)

TRouter.unregisterRoute("/dynamic-demo")   // 注销
```

> ⚠️ 动态注册的页面**也必须在 `AndroidManifest.xml` 里注册过**。路由表只是"知道有这个路径"。
> 类名写错了怎么办？用 `TRouter.checkRouteTargets()` 一查便知（见 [5.14](#514-自检注册了但打不开的路径)）。

要一次改一批（撤几条、上几条），用这个——**要么全部成功，要么一条都不改**：

```kotlin
val ok = TRouter.applyRouteConfig(
    removes = listOf("/old/a"),
    adds = listOf(metaA, metaB),
)
```

想保存下来、下次启动再读回：

```kotlin
TRouter.saveDynamicRoutes(file)    // 只导出"运行时注册的"，不含注解注册的
TRouter.loadDynamicRoutes(file)    // 读回来，并替换当前这批
```

### 5.8 导出导入整份路径表

```kotlin
val json = TRouter.exportRouteMapJson()      // 导出当前全部路径（含注解注册与运行时注册）

val ok = TRouter.importRouteMapJson(json)    // 导入为"覆盖层"：与已有路径冲突就整批失败、什么都不改
```

> 💡 设计上是"注解注册的路径是底座，导入的只能往上叠"，避免服务端下发的东西把基础功能覆盖掉。

### 5.9 路径别名

老路径改名了，但老链接、老代码还在用：

```kotlin
TRouter.registerRouteAlias("/old-second", RouterContract.PATH_SECOND)   // 精确别名
TRouter.registerRouteAlias("regex:/user/\\d+", RouterContract.PATH_ABOUT) // 正则别名：一类路径指向同一页
```

> ⚠️ 别名不能和已注册的路径重名（会注册失败并告诉你）。别名只在"查不到这个路径"时才生效。

### 5.10 模块之间用接口调用

页面跳转之外，模块之间还常常需要"你提供能力、我来用"。这时不必互相 import 实现类：

```kotlin
// 公共模块里定义接口
interface ILoginApi { fun token(): String }

// 提供方注册实现
TRouter.registerService(ILoginApi::class.java, LoginApiImpl())

// 使用方按接口取（没注册就是 null，自己判空即可）
val api = TRouter.findService(ILoginApi::class.java)
```

### 5.11 跨进程传业务对象

系统规定跨进程只能传基础类型和字符串。要传自己的业务对象，通常得手写一大坨序列化代码——
TRouter 把这件事交给编译器生成：**给数据类加一个注解就行**。

```kotlin
@RemotePojo
data class DemoReport(
    val id: String,
    val count: Int,
    val ok: Boolean,
    val tags: List<String>,
    val inner: DemoInner?,        // 嵌套对象也可以，null 也能正确传
)
```

用法（生成的编解码代码叫 `TRouterPojo_DemoReport`）：

```kotlin
val bundle = Bundle().apply { TRouterPojo_DemoReport.pack(this, report) }   // 打包
val restored = TRouterPojo_DemoReport.unpack(bundle)                        // 还原
```

支持的字段类型（**不在这个范围内的类型，编译时就报错**，不会到线上才发现）：

- `String`、`Int`、`Long`、`Float`、`Double`、`Boolean`（都可以为 null）；
- 枚举（按名字传）；
- `List<String>`、`List<Int>`、`List<Long>`；
- 同一个模块里的另一个 `@RemotePojo` 类。

> ⚠️ 两条限制：嵌套的对象必须**和它写在同一个模块**；`Date`、`Map` 这类类型要先自己转成上面的类型。
> 报错信息里会写清楚支持哪些、你的字段是什么。

### 5.12 跨进程调接口

如果你要"调用另一个进程里的方法"，也不想手写参数打包，用 `@RemoteApi` 标注接口：

```kotlin
@RemoteApi
interface DemoStatsApi {
    fun count(q: String, onResult: (Int) -> Unit)                                 // 基础类型结果
    fun report(id: String, onResult: (DemoReport) -> Unit)                        // 结果也可以是上面那种对象
    fun summarize(tag: String, level: DemoLevel, values: List<Int>, onResult: (String) -> Unit)
}
```

约定只有一条：**最后一个参数是回调**（拿结果的），方法本身返回 Unit。

远端进程注册实现（写一次）：

```kotlin
TRouterRemoteApi_DemoStatsApi.register(DemoStatsApiImpl())
```

调用方注册"怎么打包参数"这套代码（写一次），然后就能像调本地接口一样用：

```kotlin
TRouter.registerRemoteApiClients(TRouterRemoteApiRegistry.all())

val api = TRouter.remoteApi(DemoStatsApi::class.java, target = "remote2") { reason ->
    // 失败会走这里：远端没实现、超时、进程挂了……都带上原因
}
api.count("abcd") { n -> statusBar("远端返回 $n") }        // 结果回到主线程
```

> 💡 好处是调用方完全看不到"打包参数、发跨进程请求、拆返回结果"这些细节；
> 失败不会被吞掉：要么走上面那个错误回调，要么在"忘了注册打包代码"时直接抛错并告诉你怎么注册。

### 5.13 从外部链接进来

```kotlin
TRouterConfig(deeplinkSchemes = setOf("myapp"))     // 允许哪些 scheme 进来（白名单）

// myapp://app/second?msg=hi  →  打开 /second，并带上 msg=hi
TRouter.navigateUri(Uri.parse("myapp://app/second?msg=hi"))
```

> ⚠️ 这一步只负责"把链接翻译成路径"。外部点击链接要唤起你的 App，还得自己在启动页配 `intent-filter`，
> 然后把拿到的链接交给 `navigateUri`。

### 5.14 自检"注册了但打不开"的路径

```kotlin
val bad = TRouter.checkRouteTargets()   // 返回"注册了、但类加载不出来"的路径清单
```

主要用于抓"运行时注册时把类名写错了"这类问题。

---

## 6. 三道自动护栏（写错了，编译就报错）

这三道护栏是"靠机器管住人"，不用指望评审时有人能看出来。

### 护栏一：路径写死？编译期就能拦

给页面的注解里直接写字符串（`@Route(path = "/second")`）会**警告**。想让团队里彻底禁掉，加一个开关：

```bash
./gradlew assembleDebug -PtrouterPathSeverity=error     # 路径写死 → 直接编译失败
```

确实需要写死的地方（比如某个模块自己定义路径常量），在这一条上声明"我知道":

```kotlin
@Route(path = "/legacy", allowLiteral = true)
```

### 护栏二：调用点写死？Lint 检查拦住

注解里的路径能查，但 `TRouter.navigate("/second")` 这种**调用点**写死，编译器生成器看不到。
这正是最容易漏的地方——所以配了一条 Lint 检查（Lint 是 Android 官方的代码检查工具）：

```kotlin
dependencies {
    lintChecks(project(":trouter-lint"))     // 只在需要用它的模块加一行
}
```

配置好后，`TRouter.navigate("/second")` 这种写法会让构建失败，并给出提示。
确实要临时写死，加 `@Suppress("TRouterHardcodedPath")` 声明一下即可。

### 护栏三：两个模块抢同一个路径？构建期直接拦

不同模块的路径冲突，编译器生成器各自都看不到（它只看自己模块）。所以提供了一个 Gradle 插件：

```kotlin
plugins {
    id("com.trouter.route-conflict")
}
```

它会自动找到所有生成过路径的模块，检查有没有"同一个路径被两个模块抢"，有就让构建失败；
也会自动挂到 `check`、`assemble` 上，不用记着手动跑（要手动跑：`./gradlew :app:verifyTRouterRoutes`）。

> 💡 顺带说一句：开启 Lint 检查时，它还真抓出了 Demo 里一个老问题——用了只有新版系统才有的方法，
> 在低版本手机上会崩。这类问题靠肉眼评审很难发现。

---

## 7. 出问题了怎么查

### 7.1 打开日志

```bash
adb logcat -s TRouter
```

日志是分段的，一次跳转的来龙去脉能完整看到：

- `[navigate][entry]` / `[navigate][exit]`：开始跳转 / 跳转结束（含耗时）
- `[GroupLoader][load]`：启动时加载各模块的页面清单
- `[interceptor][...]`：拦截器的执行过程与决定
- `[remote][send]` / `[remote][recv]`：跨进程请求发出 / 收到结果
- `[route][...]` / `[service][...]`：路径和服务注册、注销

**同一次点击的日志都带同一个编号**（`traceId`），从入口一直贯穿到跨进程的收发，
搜这个编号就能把一条链路完整拉出来。

日志默认关着，配置里 `isDebug = true` 才打印。想接到自己公司的日志系统：

```kotlin
TRouterConfig(logSink = { line -> MyLogger.d(line) })
```

### 7.2 想知道"这个页面到底是不是路由打开的"

框架打开页面时，会往页面参数里塞一些信息。页面上写一行就能显示出来：

```kotlin
val evidence = RouteLaunch.describe(intent)
// "✓ 本次由 TRouter 打开 · path=/second · kind=ACTIVITY · traceId=a1b2c3d4 · 解析 3ms"
// 如果不是路由打开的（比如被别的入口直接唤起），会显示"未带路由信息"
```

### 7.3 常见问题

**点了没反应 / 页面没打开？**
先看返回结果是什么：`NotFound` 说明路径没注册（忘了在汇总表里加模块？忘了 `init`？）；
`Blocked` 说明被拦截器拦了——看拦截器的决定日志。

**页面能开，但状态栏显示"未走路由"？**
说明这个页面是被别的入口打开的（比如外部链接直接拉起），不是 bug。

**跳转用普通方式报"请改用 navigateAsync"？**
说明链路上有一个需要等待的拦截器。要么用 `navigateAsync`，要么让那个拦截器当场就给出结果。

**跨进程调用拿不到结果？**
看 `[remote][send]` 有没有发出去、`[remote][recv]` 有没有回来。没发出去通常是配置问题
（服务没配、路径没加 `@CrossProcess`）；没回来通常是目标进程那边没有初始化（每个进程都会各自跑 Application）。

**跑自动化测试时点击"没反应"？**
先确认模拟器**关掉了动画**（三个动画开关），否则测试框架会拒绝点击，症状看起来像代码坏了：

```bash
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
```

---

## 8. 演示工程：点一遍就懂了

工程里有一个自带说明的示例 App，**每一行点进去就能看到效果和解释**，不用读代码。

```bash
./gradlew :app:installDebug          # 装到手机/模拟器
```

打开后从上往下点，大致是这样：

| 场景 | 点它会怎样 |
|---|---|
| S01 | 打开一个新页面 |
| S02 | 打开一个"页面片段"（Fragment） |
| S03 | 打开另一个模块里的页面（验证跨模块跳转） |
| S04 | 打开一个不存在的页面，看兜底提示 |
| S08 | 开关：把某个页面临时拦住（像登录校验） |
| S09 | 开关：把一个页面换成另一个页面（灰度/兜底） |
| S11 | 把页面开在第二个进程里（页面上会显示它自己的进程号） |
| S13 / S14 | 运行时注册一条路径，再打开它 |
| S19 / S20 | 跳转时带参数 → Activity 页 / Fragment 页 |
| S21 | 带参数跳到第二个进程的页面 |
| S22 | 打开页面，并在它返回时拿到数据 |
| S23 / S24 | 打开开关让"跳转前先等一会儿"，再用异步方式跳转 |
| S25 | 检查一直没结果时的超时保护 |
| S26 | 把页面开在第三个进程里 |
| S27 | 两个进程各有自己的接口，互不串台 |
| S28 | 跨进程传一个业务对象（不用手写序列化） |
| S29 | 像调本地接口一样，调另一个进程里的接口 |

页面底部还会列出**当前所有可用的路径**，以及每条路径来自哪个模块。

**自动化测试**：目前有 98 个手机上的用例（21 个测试类）+ 7 个"检查规则"自己的单元测试，
覆盖跳转、拦截器、异步拦截器、动态路由、深链、别名、跨进程（含三个真实进程）、对象传输、跨进程调接口等，
最近一次全量结果见 `docs/reports/final/最终回测报告.md`（全部通过）。跑方法：

```bash
./gradlew :app:connectedDebugAndroidTest
```

---

## 9. 现在有什么、还没做什么

**已经能用、也有测试覆盖的**：

- 基本跳转（页面、页面片段）、参数传递、拿到返回值
- 拦截器（拦下 / 改道 / 前后包裹 / 只给某个页面挂 / 运行中随时增删）
- 异步拦截器（等待联网检查，带超时与取消）
- 跨进程：多个独立进程、跨进程服务调用、跨进程传对象、跨进程调接口
- 运行时注册路径、路径别名、整份路径表导出/导入
- 外部链接进入、页面自检
- 三道编译期/构建期护栏（路径写死、调用点写死、模块间路径冲突）

**还没做的（这里写清楚，避免误会）**：

- **模块自动初始化**：现在各模块的初始化代码要你自己在 Application 里写，还没做成"模块自动注册自己"；
- **页面之间的全局事件**：模块之间发消息/收消息这类能力还没有，需要的话先用接口调用（[5.10](#510-模块之间用接口调用)）；
- **还没发布到 Maven 中央仓库**，所以接入要拷源码模块。

**版本环境**（这是本工程验证过的组合，别的组合理论上能用但没逐个验证）：

| 项目 | 版本 |
|---|---|
| Android 最低版本 | Android 7.0（API 24） |
| 编译/目标版本 | API 37 |
| AGP / Gradle | 9.3.2 / 9.5.0 |
| Kotlin / KSP | 2.2.10 / 2.2.10-2.0.2 |
| JDK | 17（Lint 检查模块要求 17） |

---

## 10. 名词小词典

| 词 | 大白话解释 |
|---|---|
| **路由（route）** | 就是"哪个路径打开哪个页面"的对应关系，像通讯录 |
| **路径（path）** | 打开页面的名字，比如 `/second` |
| **注解（annotation）** | 写在代码上的标记，比如 `@Route(...)`，用来告诉工具"这个页面归我管" |
| **KSP / 编译期生成** | 一个在编译时帮你**自动生成代码**的工具。本项目用它生成"页面清单""对象打包代码"等，省掉手工代码 |
| **Bundle** | Android 自带的"参数袋子"，页面之间、进程之间传参数都用它 |
| **拦截器** | 跳转前统一跑的一段检查逻辑（登录、灰度、换页…） |
| **同步 / 异步** | 同步＝当场等结果（会卡住当前线程）；异步＝先去做别的事，结果回来再继续（不卡界面） |
| **主线程** | 负责画界面的那个线程。在它上面等网络/等磁盘会让界面卡住，所以要避免 |
| **进程 / 多进程** | 一个 App 可以拆成几个"独立运行的小房子"，互不影响（一个崩了不至于全崩） |
| **AIDL** | Android 提供的"进程之间通信"的方式，本项目内部用它传请求和结果 |
| **白名单** | 允许名单。这里是"允许哪些页面被跨进程打开"的清单 |
| **Lint** | Android 官方的代码检查工具，能在构建时按规则挑出问题 |
| **Gradle 插件** | 给构建过程加一步自定义检查/任务的方式 |
| **序列化 / Parcelable** | 把对象变成"能传输的数据"的过程。Android 传统做法要求手写一堆代码，本项目用注解自动生成 |
| **动态代理** | 运行期自动生成一个"假的接口实现"，你把调用交给它，它负责转发（这里用来把接口调用转成跨进程调用） |
| **超时** | 等太久就放弃，避免一直卡着 |

---

## 11. 更多文档

| 文档 | 内容 |
|---|---|
| `docs/demo/README.md` | 演示工程逐场景导览 |
| `docs/我们与开源Router差距分析.md` | 与市面上开源方案的能力对比（早期版本，部分内容已随新功能过时） |
| `docs/开源Router调研.md` | 对 ARouter / WMRouter / TheRouter / DRouter 的调研 |
| `docs/reports/final/最终回测报告.md` | **全量回测结果（测了什么、怎么跑、结果如何、还剩什么限制）** |
| `docs/reports/backlog/` | 各阶段改动与测试证据（含运行日志） |
| `docs/V1.0-…` ~ `docs/V5.0-…` | 早期各阶段的设计方案（术语较老，仅供追溯） |

### 完整接入示例

一个电商 App：`feature-order`（下单）要打开 `feature-user`（用户中心）的页面，两个模块互相不认识。

```kotlin
// feature-user：用户中心页
@Route(path = RouterContract.PATH_USER)
@Interceptor(names = ["login"])          // 需要登录校验
class UserProfileActivity : ComponentActivity()

// 公共模块：路径常量
object RouterContract {
    const val PATH_USER: String = "/user"
}
```

```kotlin
// 主模块：汇总各模块页面清单
object AppRouteRegistry : GroupLoaderRegistry {
    override fun loaders(): List<GroupLoader> =
        com.myapp.generated.TRouterGroupRegistry.loaders() +
            com.myapp.feature.order.generated.TRouterGroupRegistry.loaders() +
            com.myapp.feature.user.generated.TRouterGroupRegistry.loaders()
}
```

```kotlin
// 主模块：初始化
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val config = TRouterConfig(
            isDebug = BuildConfig.DEBUG,
            // 告诉框架"去哪些模块生成的表里查：这个页面需要哪些拦截器"
            targetInterceptorResolver = { cls ->
                com.myapp.generated.TRouterTargetInterceptorNames.namesOf(cls) +
                    com.myapp.feature.user.generated.TRouterTargetInterceptorNames.namesOf(cls)
            },
            onLost = { path -> reportMissingRoute(path) },
        )
        TRouter.init(this, config)
        TRouter.install(AppRouteRegistry)
        TRouter.bindTargetInterceptor("login", LoginInterceptor())
    }
}
```

```kotlin
// feature-order：下单页要打开用户中心（完全不 import feature-user 的类）
fun openUserCenter(activity: Activity) {
    val bundle = Bundle().apply { putString("from", "order") }
    when (val result = TRouter.navigate(RouterContract.PATH_USER, bundle)) {
        is TRouterResult.Blocked -> toast(activity, result.reason)   // 比如"未登录"
        else -> Unit
    }
}
```

---

*这份文档按仓库当前代码编写。如果代码更新了而文档不一致，以代码和测试为准，并请顺手改文档。*
