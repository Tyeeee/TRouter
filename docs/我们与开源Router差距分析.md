# 我们实现 vs 主流开源 Router —— 差距分析

> **先说人话**：这份文档回答一个问题——"我们和别人比，差在哪、强在哪"。
> 结论：论"页面跳转内核做得对不对、好不好测"，我们属于第一梯队；论跨进程，主要对着滴滴 DRouter 比；
> 论"功能多不多"，我们不是最好的（TheRouter 功能面最大）。
> 下面把差距逐条列出来（编号 G1～G11，就是"第 1 条差距"到"第 11 条差距"），并标上严重程度和建议优先级。

> 结论先说（不糊弄）：**按"页面路由导航内核的正确性/可测性"，我们处于严谨第一梯队；跨进程能力上滴滴 DRouter 是主要对表对象（见 `docs/开源Router调研.md` 第 4 节）；按"功能广度"，我们不是最优**——TheRouter 功能面最大。差距集中在：深链/URI、跨模块服务(DI)、导航结果回调、自动收参与对象传参、正则/多 path、远端路由表接入、路由合法性编译期检查等。下面逐项列出，并标上严重度与建议优先级。

---

## A. 我们相对主流的独有优势（保留项，别"为了对齐别人而砍掉"）

| 能力 | 说明 |
|---|---|
| 真实跨进程路由（AIDL + @CrossProcess + 双进程独立路由表 + 参数回显）<br>（跨进程＝一个 App 里拆出的几个独立进程互相调用；AIDL＝Android 提供的进程间通信方式） | 主流三家（ARouter/WMRouter/TheRouter）都没有内建；**滴滴 DRouter 内建了跨进程**（Router/Service/共享内存，见 `docs/开源Router调研.md` 第 4 节）——我们的差异点是：标准 AIDL + 白名单 + 可回测，语义收敛、可移植性强 |
| 拦截器动态增删（L2）+ 目标级注解（L3）+ 洋葱包裹链（L4）<br>（L2/L3/L4 是待办清单里的三条：运行期增删拦截器／把拦截器挂在具体页面上／让拦截器能把后续动作包起来） | ARouter 只有一张全局 priority 列表，没有包裹、不能动态增删、也没有目标级 |
| 跨模块冲突**构建期**拦截（L1 扫描任务）<br>（L1＝编译期就发现两个模块抢同一个路径） | ARouter/WMRouter/TheRouter 多数是运行时告警或直接合并覆盖 |
| 统一密封结果（Success/NotFound/Blocked/NotInitialized，无 null）+ traceId 全链贯穿（hop/redirect/跨进程 origin）<br>（traceId＝给一次跳转编个号，全链路日志都能搜到它） | 可测性与可观测性明显更严 |
| 证据驱动的测试底座 + 真实设备 Espresso/AIDL/并发回归<br>（Espresso＝Android 官方的 UI 自动化测试框架） | 开源工程普遍缺少系统性的回测文档 |

## B. 差距清单（对"我们的案例应该最优"的诚实反驳）

| # | 差距 | 主流参照 | 严重 | 建议 |
|---|---|---|---|---|
| G1 | 第 1 条差距：**没有把"外部链接"翻译成内部路径的那一层**，也就是**无 URI/Scheme/DeepLink 解析层**，结果是外部链接/浏览器/H5 无法进路由 | ARouter/WMRouter/TheRouter 全支持 | 高 | P1：加"URI/Scheme → path"适配入口（可以配置 scheme 前缀来解析），不改导航内核 |
| G2 | 第 2 条差距：**服务层**，就是"一个模块要用另一个模块的能力时，不必 import 对方的实现类" | ARouter/WMRouter/TheRouter/DRouter | — | ✅ 已实施（批3，即第三批改动）：进程内 `registerService/findService` + **跨进程服务** `callRemoteService`/`registerRemoteEndpoint`（真实 AIDL，:remote 注册端点、host 调用并校验执行 pid；失败=统一错误串） |
| G3 | 第 3 条差距：**导航结果回调**，就是"打开页面，并在对方返回时拿到结果" | TheRouter requestCode / ARouter NavigationCallback | — | ✅ 已实施（批2，即第二批改动）：`TRouter.navigateForResult(path, requestCode)`，全链贯穿 requestCode，无前台=明确 Blocked；ResultEcho 页 S22 全链路演示 |
| G4 | 第 4 条差距：**参数收参便利**，就是"页面里取参数，不用自己写一堆样板代码" | ARouter @Autowired | — | ✅ 已实施（批2，即第二批改动）：`RouteArgs` 类型化收参（str/int/long/double/bool/strings/Serializable/Parcelable，含跨进程边界说明），页面已采用 |
| G5 | 第 5 条差距：**path 匹配粒度**——只能用精确字符串匹配；没有正则，也没有"多 path↔一页"（正则＝能匹配一类字符串的写法） | TheRouter/WMRouter | 中 | P2（多端统一需要时再做） |
| G6 | 第 6 条差距：**路由表 JSON 导出/覆盖**，就是把"路径→页面"整份表导成 JSON，也能导进来覆盖 | TheRouter | — | ✅ 已实施（批3，即第三批改动）：`exportRouteMapJson/importRouteMapJson`（规范 JSON 自含编解码，导入=动态覆盖层，冲突/非法→整批零变更） |
| G7 | 第 7 条差距：**路由目标合法性检查**，就是检查"注册了的这条路径，指向的页面类到底存不存在" | TheRouter 校验 | — | ✅ 已实施（批1，即第一批改动）：`TRouter.checkRouteTargets()` 检出动态注册错类名等迟发现问题并告警） |
| G8 | 第 8 条差距：**目标类重复加载**，就是同一个页面类别反复加载 | — | — | ✅ 已实施（批1，即第一批改动）：ConcurrentHashMap 按进程缓存（首次加载一次；ConcurrentHashMap＝Java 里一种线程安全的键值表） |
| G9 | 第 9 条差距：**模块初始化编排（自动/懒加载/依赖图循环检测）缺失**，就是"各模块谁先初始化、什么时候初始化"框架没替我们安排 | TheRouter FlowTaskExecutor | 低（scope 外） | 不入本期 |
| G10 | 第 10 条差距：**无 Action/全局回调事件系统**，就是没有"一处发事件、处处能收到"这一套 | TheRouter ActionManager | 低 | 不入本期 |
| G11 | 第 11 条差距：**拦截器优先级**，就是多个拦截器谁先跑，能不能指定 | ARouter priority | — | ✅ 已实施（批1，即第一批改动）：`RouteChainMember.priority`（默认 0 保持顺序，稳定降序），与 L2/L4 并存 |

## C. 结论与建议路线

1. **定位声明**：本项目当前是「高正确性、高可测、真跨进程的**路由导航内核**」，不是「组件化全家桶」；在这个定位里，我们没有明显短板。
2. 如果要向"最优完整方案"看齐，**最小必修 = G7 + G8 + G3**（成本低、收益明确），**可选增益 = G1 + G4 + G6(导出/下发复用 apply)**；**重大决策 = G2 服务层**（要不要引入需要你拍板，免得又背上一个"全家桶"包袱）。
3. 顺序建议：先把重构批次 C（第三批改动）收尾 → 做 P0（G7/G8）与 G3 基线（P0＝最高优先级，先做；P1/P2 依次往后）→ 统一回测（覆盖全部场景，并把 B 表——就是上面那张差距清单——变成回测对照矩阵）→ 再由你决定 P1/P2。
