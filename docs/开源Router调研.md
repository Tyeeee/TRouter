# 开源 Android Router 调研（优缺点总结）

> 目的：为「与我们的实现对照」提供客观基准。主查对象：**ARouter（阿里）**、**WMRouter（美团）**、**TheRouter（货拉拉/开源）**，辅以市面上其余框架概览。
> 证据层级：TheRouter 能力清单与三方对比表取自其官方文章（一手）；ARouter/WMRouter 的个别条目部分依赖该官方对比表与通用认知（二手，已标注）。
> 链接：[TheRouter 官方能力介绍（阿里云开发者社区转载）](https://developer.aliyun.com/article/1012343)、[TheRouter - OSChina](https://www.oschina.net/p/therouter)、[ARouter 简单分析](https://www.e-com-net.com/article/1402162481647898624.htm)、[Android主流Router库对比（ARouter、ActivityRouter）](https://my.oschina.net/jimmysuncpt/blog/4913845)、[优秀的Android路由框架整理](https://www.cnblogs.com/renhui/articles/19541475)、[WMRouter 仓库](https://github.com/Meituan-Dianping/WMRouter)。

---

## 1. ARouter（阿里，Java）

| 维度 | 内容 |
|---|---|
| 能力 | 注解 `@Route`（Activity/Fragment）；**IProvider 服务层**（跨模块依赖注入）；`@Autowired` 自动收参；全局拦截器（按 priority）；**URI/Scheme 深链**；导航降级/全局处理器；分组路由（init 时可按组 lazy）；debug 路由表 dump |
| 优点 | 生态最大、文档多；能力全面（页面 + 服务 + 注入 + 深链都覆盖）；久经考验；有降级与 notfound 扩展点 |
| 缺点（据 TheRouter 官方对比表与社区分析） | **运行时扫描 dex/反射实例**、init 性能损耗较大；拦截器为全局列表（无 Activity 级/目标级，包裹式洋葱缺失）；多 path→同一页面需重复注解；开启文档导出破坏增量编译；官方维护节奏趋缓，模块化新问题（远端表/H5 降级/模块自动初始化）不含 |
| 二手说明 | “运行时扫描 dex”结论出自 TheRouter 对比表；其余为该框架公开 API 常识 |

## 2. WMRouter（美团）

| 维度 | 内容 |
|---|---|
| 能力 | 分层路由（Router + Handler）；**ServiceLoader（IProvider 式服务）**；URI/Scheme + **正则 path**；Activity 级拦截器（uri 拦截链）；Fragment 支持 |
| 优点 | 架构分层清晰（UriHandler 责任链设计受好评）；正则/URI 匹配灵活；服务层解耦完整 |
| 缺点（据 TheRouter 对比表 + 维护状态） | **项目基本停止活跃维护**；运行时读文件/反射性能中；不支持动态注册路由、远端路由表、多 path 合并；增量编译弱 |
| 二手说明 | 功能对照列主要依据 TheRouter 官方表；仓库 README 未能拉取（网络受限），标注二手 |

## 3. TheRouter（货拉拉开源，Kotlin）

| 维度 | 内容 |
|---|---|
| 能力（官方清单） | Navigator：Activity/Fragment、path 正则、**多 path↔一页**、json 路由表导出、**远端下发路由表/H5 降级**、**任意 object 跨模块传参**、四层拦截、requestCode/结果回调、打开第三方库页面；ServiceProvider（DI）；FlowTaskExecutor（模块自动/懒加载初始化、循环依赖编译期检测）；ActionManager（全局回调/优先级/调用链）；迁移工具 |
| 优点 | 功能广度当前最强；**编译期生成聚合、无运行时扫描、无反射加载路由表**；增量编译 & 热修复友好；模块化全家桶一条龙 |
| 缺点 | 重与复杂：需要配套 Gradle 插件 + 全套约定，侵入大；对中小项目过重；部分能力（Flow/Action）宣布拆分为可选但仍属整体方案；绑定其工程组织方式后才好用 |
| 一手说明 | 本文能力清单来自官方介绍文章（上文链接） |

## 4. DRouter（滴滴 · didi/DRouter）

> 依据：仓库 README 一手（[didi/DRouter](https://github.com/didi/DRouter)）；介绍文 [滴滴开源 DRouter](https://juejin.cn/post/6975818153381068831)。

| 维度 | 内容 |
|---|---|
| 能力 | URI 导航 **Activity/Fragment/View/RouterHandler**（正则+占位符）；**ActivityResultLauncher** 适配；Handler/Activity 异步完成(hold)与超时；指定执行线程；**全局+局部拦截器（可名字引用、AOP）**；接口/基类导航 **Service**（别名、多维过滤器、任意构造器、单例）；**动态注册 Handler/Service 并绑定生命周期自动解绑**；**跨进程执行 Router 与 Service**（无需提前绑定、如本地调用、客户端/服务端自动重连）、共享内存；VirtualApk 插件；AndroidX |
| 优点 | 功能全面且**性能导向**（增量编译/多线程扫描；初始化点对点加载，无反射无遍历，异步）；**ServiceLoader 强**（实例化+过滤+优先级）；**跨进程通信易用**（无绑定感知、自动重连、共享内存）；服务与路由统一 |
| 缺点/注意 | 重且与滴滴组件化体系绑定（需要配套 Gradle 插件并按 AGP 版本选择插件）；跨进程依赖其自有协议/共享内存体系，与 Android 系统级 AIDL/进程模型的可移植性不同；社区规模相对小；页面/服务验证材料少、缺系统性公开回测 |
| 与我们的相关性 | **它证明“路由框架内建跨进程”是现实需求**（与本项目理念一致）；其跨进程面更宽（Router+Service+共享内存），但我们的跨进程是**标准 AIDL + @CrossProcess 白名单 + 双进程独立路由表 + 可回测证据**，语义更收敛 |

## 5. 其余概览（简要）

- ActivityRouter、JLRouter（沪江）等：早年方案，功能与维护均不活跃，参考价值有限（对比文见链接）。
- Jetpack Navigation：官方导航（页面图/转场/深层链接）但**不是组件化解耦路由**，与本课题边界不同，未纳入矩阵。

## 6. 主流横向印象（用于与我们对标的总结）

- **广度之王**：TheRouter（页面+服务+注入+动态化+初始化+动作系统全包，代价是重）。
- **成熟生态**：ARouter（服务+深链+注入齐全，代价是运行时扫描反射、性能与增量弱）。
- **设计标杆**：WMRouter（UriHandler 分层/正则清晰，但已停止活跃维护）。
- 共同注意点（对我们有价值）：对「拦截器运行时可动态增删、链的并发正确性」普遍缺讨论与测试文化；大多以「能不能跳」为验收，缺可复现回归证据体系。跨进程能力方面 **DRouter 是主要参照**（Router/Service/共享内存），其余三家基本未内建。
