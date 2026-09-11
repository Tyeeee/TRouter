# TRouter 代码审核报告（逐功能 · 2026-09-06）

> **先说人话**：这份报告是「一个功能一个功能地读代码」的结果——每发现一个问题，都写清楚**是什么问题、有多严重、怎么处理的**，修完再跑一遍老用例确认没把别的地方弄坏。
> 审核范围：core（路由表／生命周期／拦截链 L2–L4／动态路由 V5+F／跨进程 V4+D）、processor/annotation、app 的装配和 demo。
> 方法：逐个文件精读 + 针对「顺序／时机／并发」逐条推演 + 关键缺陷先修复后回归。
> 结论速览：**高 1 个已修复、中 1 个已修复、低级别和说明若干（见 §7 待回测确认清单）**；修复后参数相关的 3 个用例 3/3 绿。

**先解释这份报告里的写法**（后文直接使用）：

- 严重等级：**高**＝会让功能出错或证据不可信；**中**＝影响面有限但确实不对；**低**＝边界情况或约定层面；**说明**＝不是问题，是设计如此；**边界／语义边界**＝设计上就到这里的极限。
- `L1`～`L4`、`F`、`D`、`V1`～`V5`：待办事项编号和版本号（项目分五个阶段做，数字越大越晚），不是功能名。
- `S16-3`、`S15 L4-4`、`F-1`、`S12-5` 这类：回归用例的编号（「锚点」就指这些专门盯着某类问题的用例）。
- **回归**＝改完代码再把相关的老用例跑一遍，确认没弄坏别的。
- `Bundle`＝Android 用来装参数的「袋子」；`Parcelable`＝Android 要求对象实现的一套「能被传输」接口；`Binder 事务 1MB`＝Android 跨进程传数据有个约 1MB 的大小上限。
- `KSP`＝编译期自动生成代码的工具；`KDoc`＝写在代码里的文档注释；`Looper`＝Android 的消息循环，一个线程一个；`worker`＝后台干活的线程。

---

## 1. V1 路由表 / 生命周期 / openTarget

| 发现 | 严重 | 处置 |
|---|---|---|
| openTarget **先写路由自证信息（RouteLaunch 元数据）、后并入用户传的 bundle** → 用户参数能把 EXTRA_PATH/KIND/GROUP/TRACE_ID 覆盖掉（页面上的「本次由路由打开」证据可以被伪造，兜底判断也会被搅乱） | 高 | **已修复**：先并入用户 bundle，路由元数据后写（保留键永远是路由赢）；FRAGMENT 的容器键改成在用户参数之后再写入。回归：S19/S20/S21 往 bundle 里塞 `EXTRA_PATH="HACKED-OVERRIDE"`，断言页面仍显示真实 path（3/3 绿） |
| FragmentContainerActivity 把**整包 intent.extras** 原样克隆成 Fragment 的 arguments，内部键 EXTRA_FRAGMENT_CLASS 漏进了业务参数 | 中 | **已修复**：克隆之后先把容器内部键删掉，再赋给 fragment |
| RouteTable 不是线程安全的（用的 LinkedHashMap），navigate/register 并发时只能靠「单写者约定」顶着 | 低(约定) | 记录为约定：动态路由 F 的 apply 已经加了 applyLock；跨线程并发场景归入统一回测专项，那时再定要不要加锁 |
| resetForTest 和 remote worker 之间可能存在「先入队的旧任务反而后执行」的窗口 | 低 | 只有一个 worker 且按 FIFO 排队，reset 的 disconnect 会先于后续 navigate 入队，顺序成立；回测的并发专项会覆盖 |
| init 可以重复调用，但路由表不会被清空（要清空得用 resetForTest） | 说明 | 设计如此，KDoc 里已注明 |

## 2. Processor / KSP 生成

| 发现 | 严重 | 处置 |
|---|---|---|
| 生成的依赖标记为 aggregating=true（V3 已修）：增量编译结果正确 | — | 复核通过；L3 新增的生成沿用同样设置 |
| @CrossProcess/@Interceptor 的校验：必须和 @Route 一起标注，缺了就报 ERROR | — | 复核通过 |
| 判断「路径是不是写死的字符串」用的是 annotationRegionText，靠「读源文件 + 扫描 class 那一行」实现；KDoc/注释里出现 "class X" 可能定位错 | 低 | 只影响 C2 的 Warning 发不发（宁可少发也不能误报，这条路径已经兜住了），回测补一例「注释干扰」的用例确认不会误报 |
| RouterContract 里常量值变了，不会触发 @Route 重新扫描（需要重新构建） | 低(已知) | 待办 L8 的已知边界，报告里留痕 |

## 3. 拦截链执行器（L4）+ 注册表（L2/L3）

| 发现 | 严重 | 处置 |
|---|---|---|
| 一次 navigate 在同一个调用栈里同步推进；链快照取自不可变拷贝 → 不会出现并发交错 | — | 复核通过（S16-3 等回归锚点正在跑） |
| proceed() 有「只能放行一次」的守卫（第二次调用直接抛错 → 按拦截器故障处理成 Blocked） | — | 复核通过（S15 L4-4） |
| 包裹型拦截器在 proceed() 已经把目标页打开之后才返回 Blocked：页面开了，结果却是 Blocked | 语义边界 | 写进文档：包裹者不该「先开后拦」；回测加一条确认不崩溃、结果明确即可 |
| Redirect 可以由链里任意一层产生 → 顶层按 hop 重新进入；重入后包裹链按新 path 重新组成（不会把外层重复套一遍） | — | 复核通过（S15 L4-5） |
| L2 的增删只影响下一次跳转（快照语义）；同一个实例重复添加会被拒绝 | — | 复核通过 |
| L3 的目标级链排在全局链之后；名字没绑定实现＝明确的 Blocked，不静默放行 | — | 复核通过；resolver 抛异常也会被捕获成 Blocked |

## 4. 动态路由 V5 + 热更 F

| 发现 | 严重 | 处置 |
|---|---|---|
| applyRouteConfig 先预校验 → 保证「一条都不落地」的原子性；remove 排在 add 之前 | — | 复核通过（F-1 冲突批零落地） |
| save/load 走 DynamicRouteCodec（解码容错，遇到坏行直接跳过） | — | 复核通过 |
| export 只导出动态路径（动态集合跟着 register/unregister 维护）；静态路径不导出 | — | 语义明确；回测补一条「静态+动态混存后 load 不误删静态」的用例 |

## 5. 跨进程 V4 + D 动态代理

| 发现 | 严重 | 处置 |
|---|---|---|
| RemoteRouter 单个 worker 串行 + 由状态迁移驱动派发（连接/入队/断开/超时都在同一个 Looper 上） | — | 复核通过（上轮重写后 26/26、二次调用回归 S12-5 都在跑） |
| bind 的冷启动/超时/悬挂解除/断开失败，都给了明确结论 | — | 复核通过 |
| D 透明代理只包装接口调用，不改变通道本身的语义（真实跨进程用例仍然绿） | — | 复核通过 |
| Bundle 跨进程有 Binder 事务的 1MB 上限，而且只该装可序列化的类型（同一个 App 里自定义 Parcelable 可行） | 边界 | 没做防御：超限会抛异常（可以被 execute 捕获成 Blocked）。**列入回测专项**：大量字符串／超限 bundle 的实际行为 |
| routeGraph/registeredRoutes 的快照线程安全（返回的是副本） | — | 复核通过 |

## 6. Bundle 参数（单进程/跨进程）

- 通道复核：Activity=intent extras；Fragment=容器克隆出的 arguments（内部键已剥掉）；跨进程=AIDL 的 `in Bundle`（同一个 App 内自定义 Parcelable 可行，跨 App 不支持——这不在本项目范围内）。
- 值类型覆盖不够（你指出来的）：目前只验证过 String/Int 少量几个键。**实现层对任意系统类型和多键是透传的，这一点已确认**，但缺「大量键／多字符串／各种类型族」的实测——全部并入回测的 §3 参数专项。

## 7. 待统一回测确认清单（审核遗留项：不影响功能，但必须验证）

1. Bundle 的广度：≥200 个键、≥50 条长字符串、String/Long/Double/Boolean/IntArray/StringArray/ArrayList<String>/同 App 自定义 Parcelable、键里带空格和 Unicode。
2. Binder 事务边界：约 1MB 超限 → 应该明确变成 Blocked 而不是崩溃（跨进程），以及单进程本来就没有这个限制的差异。
3. 屏幕旋转后 Fragment 的参数是否保留（FragmentContainerActivity 已按 savedInstanceState 不重复 add；参数来自 arguments 重建，会自动保留）。
4. 静态+动态混存时，loadDynamicRoutes 不会误删静态路径。
5. 注释/KDoc 干扰「字面量判定」时，C2 不会误报也不会漏报。
6. 拦截链的并发改动（navigate 过程中 add/remove 的用例已经在跑）＋多线程同时 navigate 同一张表，行为要确定。
7. 进程被杀之后重连（kill 掉 :remote → 再 navigateRemote 能自动重新 bind）。

> 说明：上面第 1、4、5、7 条是「必须真跑一遍的边界/断言」；第 3、6 条看环境决定是纳入专项还是标注成约定。
