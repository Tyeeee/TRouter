# TRouter 代码审核报告（逐功能 · 2026-09-06）

> 范围：core（路由表/生命周期/拦截链 L2–L4/动态路由 V5+F/跨进程 V4+D）、processor/annotation、app 装配与 demo。
> 方法：逐文件精读 + 针对「顺序/时机/并发」逐条推演 + 关键缺陷先修复后回归。
> 结论速览：**高 1 已修复、中 1 已修复、低/说明若干（见 §7 待回测确认清单）**；修复后参数相关 3/3 绿。

---

## 1. V1 路由表 / 生命周期 / openTarget

| 发现 | 严重 | 处置 |
|---|---|---|
| openTarget **先写 RouteLaunch 元数据、后并入用户 bundle** → 用户可覆盖 EXTRA_PATH/KIND/GROUP/TRACE_ID（页面运行时证据可被伪造、降级判断受扰） | 高 | **已修复**：用户 bundle 先并入，路由元数据后写（保留键恒胜）；FRAGMENT 容器键在用户参数后再断言。回归：S19/S20/S21 的 bundle 内塞 `EXTRA_PATH="HACKED-OVERRIDE"`，断言页面仍显示真实 path（3/3 绿） |
| FragmentContainerActivity 把**整包 intent.extras** 克隆成 Fragment arguments，内部键 EXTRA_FRAGMENT_CLASS 泄漏进业务参数 | 中 | **已修复**：克隆后移除容器内部键再赋给 fragment |
| RouteTable 非线程安全（LinkedHashMap），navigate/register 并发只依赖“单写者约定” | 低(约定) | 记录为约定：动态路由 F apply 已加 applyLock；跨线程并发场景归入统一回测专项再定是否加锁 |
| resetForTest 与 remote worker 之间可能有“先入队的旧任务后执行”窗口 | 低 | 单 worker FIFO，reset 的 disconnect 先于后续 navigate 入队，顺序成立；回测并发专项覆盖 |
| init 可重复调用但路由表不清空（须 resetForTest） | 说明 | 设计如此，KDoc 已注明 |

## 2. Processor / KSP 生成

| 发现 | 严重 | 处置 |
|---|---|---|
| 生成依赖 aggregating=true（V3 已修）：增量正确 | — | 复核通过；L3 新增生成沿用 |
| @CrossProcess/@Interceptor 校验：必须同标 @Route，缺失 ERROR | — | 复核通过 |
| 字面量判定 annotationRegionText 依赖“读源文件 + class 行扫描”，KDoc/注释含 “class X” 可能误定位 | 低 | 仅影响 C2 Warning 是否发出（宁缺勿误报路径已兜底），回测补一例注释干扰类确认无误报 |
| RouterContract 常量值变更不触发 @Route 源重扫（需重建） | 低(已知) | backlog L8 已知边界，报告留痕 |

## 3. 拦截链执行器（L4）+ 注册表（L2/L3）

| 发现 | 严重 | 处置 |
|---|---|---|
| 单次 navigate 同步调用栈推进；链快照取自不可变拷贝 → 无并发交错 | — | 复核通过（S16-3 等回归锚点在跑） |
| proceed() 单次放行守卫（二次调用抛错→按拦截器故障 Blocked） | — | 复核通过（S15 L4-4） |
| wrapper 在 proceed() 已打开目标后返回 Blocked：页面已开但结果 Blocked | 语义边界 | 文档化：包裹者不应“开后再拦”；回测加一条确认不崩溃、结果明确即可 |
| Redirect 由链中任意层产生 → 顶层按 hop 重入；重入后 wrapper 链按新 path 重新构成（不会重复套外层） | — | 复核通过（S15 L4-5） |
| L2 增删只影响下一次（快照语义）；同实例重复拒绝 | — | 复核通过 |
| L3 目标级链在全局后；未绑定名=明确 Blocked 不静默 | — | 复核通过；resolver 抛错也被捕获为 Blocked |

## 4. 动态路由 V5 + 热更 F

| 发现 | 严重 | 处置 |
|---|---|---|
| applyRouteConfig 预校验→零落地原子性；remove 先于 add | — | 复核通过（F-1 冲突批零落地） |
| save/load 走 DynamicRouteCodec（容错解码跳过坏行） | — | 复核通过 |
| export 仅动态路径（动态集合随 register/unregister 维护）；静态不导出 | — | 语义明确；回测补“静态+动态混存后 load 不误删静态”用例 |

## 5. 跨进程 V4 + D 动态代理

| 发现 | 严重 | 处置 |
|---|---|---|
| RemoteRouter 单 worker 串行 + 状态迁移驱动派发（连接/入队/断开/超时都在同一 Looper） | — | 复核通过（上轮重写后 26/26、二次调用回归 S12-5 均在跑） |
| bind 冷启动/超时/悬挂解除/断开失败明确化 | — | 复核通过 |
| D 透明代理仅包装接口调用，不改变通道语义（真实跨进程用例仍绿） | — | 复核通过 |
| Bundle 跨进程有 Binder 事务 1MB 上限、且应只含可序列化类型（同 App 自定义 Parcelable 可行） | 边界 | 未做防御：超限会抛异常（可被 execute catch 成 Blocked）。**列入回测专项**：大量字符串/超限 bundle 行为 |
| routeGraph/registeredRoutes 快照线程安全（返回副本） | — | 复核通过 |

## 6. Bundle 参数（单进程/跨进程）

- 通道复核：Activity=intent extras；Fragment=容器克隆 arguments（已剥离内部键）；跨进程=AIDL `in Bundle`（同 App 自定义 Parcelable 可行，跨 App 不支持——本项目范围外）。
- 值类型覆盖不足（你指出）：当前仅验证 String/Int 少量键。**已确认实现层对任意系统类型与多键是透传**，但缺“大量键/多字符串/类型族”的实测——全部并入回测 §3 参数专项。

## 7. 待统一回测确认清单（审核遗留项，不阻塞功能但必须验证）

1. Bundle 广度：≥200 键、≥50 条长字符串、String/Long/Double/Boolean/IntArray/StringArray/ArrayList<String>/同 App 自定义 Parcelable、键内含空格与 Unicode。
2. Binder 事务边界：~1MB 超限 → 应明确 Blocked 不崩溃（远程）与单进程无此限制差异。
3. 旋转重建 Fragment 参数保留（FragmentContainerActivity 已按 savedInstanceState 不重复 add；参数来自 arguments 重建自动保留）。
4. 静态+动态混存 loadDynamicRoutes 不误删静态。
5. 注释/KDoc 干扰字面量判定无 C2 误报/漏报。
6. 拦截链并发改动（navigate 中 add/remove 已在跑）＋多线程 navigate（不同线程同时 navigate 同表）行为确定。
7. 进程被杀后重连（kill :remote → 再次 navigateRemote 自动重 bind）。

> 说明：以上 1、4、5、7 为“需真实跑一遍的边界/断言”，3、6 视环境纳入专项或标注约定。
