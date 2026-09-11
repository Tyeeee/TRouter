# 开源 Android 路由框架调研（优缺点总结）

> **先说人话**：这份文档是"别人家的路由框架长什么样"的调研笔记，用来当我们自己实现的对照尺子。
> 重点看四个：ARouter（阿里）、WMRouter（美团）、TheRouter（货拉拉开源）、DRouter（滴滴）。
> 每条能力都写清楚了是"从官方文章看来的（一手）"还是"从别人对比表和常识推来的（二手）"，避免把二手的当成事实。

> 目的：给"拿我们的实现去对照"提供一把客观尺子。主要调研对象：**ARouter（阿里）**、**WMRouter（美团）**、**TheRouter（货拉拉/开源）**，另外附上市面上其他框架的概览。
> 证据层级：TheRouter 的能力清单和三方对比表来自它的官方文章（一手）；ARouter/WMRouter 的个别条目部分依赖这份官方对比表和通用认知（二手，已标注）。
> 链接：[TheRouter 官方能力介绍（阿里云开发者社区转载）](https://developer.aliyun.com/article/1012343)、[TheRouter - OSChina](https://www.oschina.net/p/therouter)、[ARouter 简单分析](https://www.e-com-net.com/article/1402162481647898624.htm)、[Android主流Router库对比（ARouter、ActivityRouter）](https://my.oschina.net/jimmysuncpt/blog/4913845)、[优秀的Android路由框架整理](https://www.cnblogs.com/renhui/articles/19541475)、[WMRouter 仓库](https://github.com/Meituan-Dianping/WMRouter)。

---

## 1. ARouter（阿里，Java）

| 维度 | 内容 |
|---|---|
| 能力 | 注解 `@Route`（贴在 Activity/Fragment 上；注解＝写在代码上的标记）；**IProvider 服务层**（IProvider 是它的服务写法，用来做跨模块依赖注入——依赖注入＝"我需要某个能力，由别人提供给我"，不用自己去 new）；`@Autowired` 自动收参（页面自己不用手写取参数的代码）；全局拦截器（按 priority 优先级排队）；**URI/Scheme 深链**（深链＝用一条外部链接直接唤起 App 里的某个页面）；导航降级/全局处理器；分组路由（init 时可按组 lazy，lazy＝用到才加载）；debug 时能把路由表 dump（导出）出来看 |
| 优点 | 生态最大、文档多；能力全面（页面 + 服务 + 注入 + 深链都覆盖）；久经考验；有降级与 notfound 扩展点 |
| 缺点（据 TheRouter 官方对比表与社区分析） | **运行时扫描 dex、用反射实例化**（dex＝Android 打包后的可执行文件格式，里面装着全部代码；反射＝运行时按名字去找类/调方法），所以 init 阶段性能损耗比较大；拦截器只是一张全局列表（没有 Activity 级/目标级，也没有包裹式的洋葱模型）；多个 path 指向同一个页面时要重复写注解；开启文档导出会破坏增量编译（增量编译＝只重编改动过的部分）；官方维护节奏变慢，模块化遇到的新问题（远端路由表/H5 降级/模块自动初始化）它不含 |
| 二手说明 | "运行时扫描 dex"这个结论出自 TheRouter 的对比表；其他是该框架公开 API 的常识 |

## 2. WMRouter（美团）

| 维度 | 内容 |
|---|---|
| 能力 | 分层路由（Router + Handler 两层）；**ServiceLoader（IProvider 式服务）**；URI/Scheme + **正则 path**（正则＝一种能匹配一类字符串的写法，比如 /user/后面跟数字都能命中）；Activity 级拦截器（uri 拦截链）；支持 Fragment |
| 优点 | 架构分层清楚（UriHandler 的责任链设计评价很好）；正则/URI 匹配灵活；服务层解耦做得完整 |
| 缺点（据 TheRouter 对比表 + 维护状态） | **项目基本停止活跃维护**；运行时读文件/反射的性能一般；不支持动态注册路由、远端路由表、多 path 合并；增量编译弱 |
| 二手说明 | 功能对照那几列主要依据 TheRouter 官方表；仓库 README 没能拉取到（网络受限），所以标为二手 |

## 3. TheRouter（货拉拉开源，Kotlin）

| 维度 | 内容 |
|---|---|
| 能力（官方清单） | Navigator：Activity/Fragment、path 正则、**多 path↔一页**、json 路由表导出、**远端下发路由表/H5 降级**、**任意 object 跨模块传参**、四层拦截（拦截器分四层）、requestCode/结果回调、打开第三方库的页面；ServiceProvider（DI，也就是依赖注入式的服务）；FlowTaskExecutor（模块自动/懒加载初始化、循环依赖编译期检测）；ActionManager（全局回调/优先级/调用链）；迁移工具 |
| 优点 | 功能广度目前最强；**编译期生成聚合、没有运行时扫描、也没有反射加载路由表**；对增量编译 & 热修复友好（热修复＝不重新发版就修线上代码）；模块化全家桶一条龙 |
| 缺点 | 重、复杂：要配套 Gradle 插件 + 一整套约定，侵入性大；对中小项目来说太重；部分能力（Flow/Action）宣布拆成可选项，但仍然属于整体方案的一部分；得先接受它那套工程组织方式才用得顺 |
| 一手说明 | 这里的能力清单来自官方介绍文章（就是上面那个链接） |

## 4. DRouter（滴滴 · didi/DRouter）

> 依据：仓库 README（一手，[didi/DRouter](https://github.com/didi/DRouter)）；介绍文 [滴滴开源 DRouter](https://juejin.cn/post/6975818153381068831)。

| 维度 | 内容 |
|---|---|
| 能力 | URI 导航 **Activity/Fragment/View/RouterHandler**（正则+占位符）；适配 **ActivityResultLauncher**；Handler/Activity 异步完成(hold)与超时（hold＝先把这次导航挂住不返回，等异步任务做完再给结果）；能指定在哪个线程执行；**全局+局部拦截器（可以按名字引用、支持 AOP）**（AOP＝在方法调用前后自动插入一段逻辑的技术）；接口/基类导航 **Service**（别名、多维过滤器、任意构造器、单例）；**动态注册 Handler/Service 并绑定生命周期自动解绑**；**跨进程执行 Router 与 Service**（不用提前绑定、调用起来像本地调用、客户端/服务端自动重连）、共享内存；VirtualApk 插件（VirtualApk＝滴滴的插件化框架）；AndroidX |
| 优点 | 功能全面而且**以性能为导向**（增量编译/多线程扫描；初始化是点对点加载，没有反射也没有遍历，异步执行）；**ServiceLoader 很强**（实例化+过滤+优先级）；**跨进程通信好用**（不用感知绑定、自动重连、共享内存）；服务和路由是统一的 |
| 缺点/注意 | 又重又与滴滴自家组件化体系绑得紧（需要配套 Gradle 插件，还要按 AGP 版本选插件）；跨进程依赖它自己的一套协议/共享内存体系，和 Android 系统级 AIDL/进程模型的可移植性不是一回事（AIDL＝Android 提供的进程间通信方式）；社区规模相对小；页面/服务方面的验证材料少，缺少系统性的公开回测 |
| 与我们的相关性 | **它证明了"路由框架内建跨进程"是真实需求**（和我们项目的理念一致）；它的跨进程面更宽（Router+Service+共享内存），但我们的跨进程是**标准 AIDL + @CrossProcess 白名单 + 双进程独立路由表 + 可回测证据**，语义更收敛 |

## 5. 其余概览（简要）

- ActivityRouter、JLRouter（沪江）等：早年方案，功能与维护都不活跃，参考价值有限（对比文章见上面的链接）。
- Jetpack Navigation：官方导航（页面图/转场/深层链接），但**它不是用来做组件化解耦的路由**，跟本课题的边界不一样，所以没纳入对比矩阵。

## 6. 主流横向印象（用来和我们对标）

- **广度之王**：TheRouter（页面+服务+注入+动态化+初始化+动作系统全包，代价是重）。
- **成熟生态**：ARouter（服务+深链+注入齐全，代价是运行时扫描反射、性能和增量编译弱）。
- **设计标杆**：WMRouter（UriHandler 分层/正则清晰，但已经停止活跃维护）。
- 共同的注意点（对我们有价值）：对"拦截器能不能在运行期动态增删、链的并发正确性"这件事，普遍缺少讨论和测试文化；大多数只以"能不能跳"当验收标准，缺少可复现的回归证据体系。跨进程能力方面，**DRouter 是主要参照**（Router/Service/共享内存），其余三家基本没有内建。
