# 我们实现 vs 主流开源 Router —— 差距分析

> 结论先说（不糊弄）：**按“页面路由导航内核的正确性/可测性”，我们处于严谨第一梯队；跨进程能力上滴滴 DRouter 是主要对表对象（见调研 §4）；按“功能广度”，我们不是最优**——TheRouter 功能面最大。差距集中在：深链/URI、跨模块服务(DI)、导航结果回调、自动收参与对象传参、正则/多 path、远端路由表接入、路由合法性编译期检查等。下面逐项列出，标注严重度与建议优先级。

---

## A. 我们相对主流的独有优势（保留项，勿“为对齐而砍”）

| 能力 | 说明 |
|---|---|
| 真实跨进程路由（AIDL + @CrossProcess + 双进程独立路由表 + 参数回显） | 主流三家（ARouter/WMRouter/TheRouter）未内建；**滴滴 DRouter 内建跨进程**（Router/Service/共享内存，见调研 §4）——我们的差异点：标准 AIDL + 白名单 + 可回测，语义收敛、可移植性强 |
| 拦截器动态增删（L2）+ 目标级注解（L3）+ 洋葱包裹链（L4） | ARouter 仅全局 priority 列表，无包裹/动态增删/目标级 |
| 跨模块冲突**构建期**拦截（L1 扫描任务） | ARouter/WMRouter/TheRouter 多为运行时告警/合并覆盖 |
| 统一密封结果（Success/NotFound/Blocked/NotInitialized，无 null）+ traceId 全链贯穿（hop/redirect/跨进程 origin） | 可测性与可观测明显更严 |
| 证据驱动的测试底座 + 真实设备 Espresso/AIDL/并发回归 | 开源工程普遍缺系统性回测文档 |

## B. 差距清单（对“我们的案例应该最优”的诚实反驳）

| # | 差距 | 主流参照 | 严重 | 建议 |
|---|---|---|---|---|
| G1 | **无 URI/Scheme/DeepLink 解析层**：外部链接/浏览器/H5 无法进路由 | ARouter/WMRouter/TheRouter 全支持 | 高 | P1：加“URI/Scheme → path”适配入口（可配置 scheme 前缀解析），不改导航内核 |
| G2 | **无跨模块服务/依赖注入层**（IProvider/ServiceProvider/ServiceLoader） | 三者全支持 | 高（若定位=组件化全家桶） | P2：新增独立服务注册层（接口+实现绑定），与页面路由解耦 |
| G3 | 导航结果回调 | TheRouter requestCode / ARouter NavigationCallback | — | ✅ 已实施（批2）：`TRouter.navigateForResult(path, requestCode)`，全链贯穿 requestCode，无前台=明确 Blocked；ResultEcho 页 S22 全链路演示 |
| G4 | 参数收参便利 | ARouter @Autowired | — | ✅ 已实施（批2）：`RouteArgs` 类型化收参（str/int/long/double/bool/strings/Serializable/Parcelable，含跨进程边界说明），页面已采用 |
| G5 | **path 匹配粒度**：仅精确字符串；无正则、无“多 path↔一页” | TheRouter/WMRouter | 中 | P2（多端统一需要时再做） |
| G6 | **远端路由表下发/H5 降级**只有本地底座（registerRoute/apply/save），无 assets 导出 json 与下发覆盖 | TheRouter 完整链路 | 中 | P2：补 RouteMap JSON 导出 + 导入覆盖(复用 apply)即可得大部分能力 |
| G7 | 路由目标合法性检查 | TheRouter 校验 | — | ✅ 已实施（批1）：`TRouter.checkRouteTargets()` 检出动态注册错类名等迟发现问题并告警） |
| G8 | 目标类重复加载 | — | — | ✅ 已实施（批1）：ConcurrentHashMap 按进程缓存（首次加载一次） |
| G9 | 模块初始化编排（自动/懒加载/依赖图循环检测）缺失 | TheRouter FlowTaskExecutor | 低（scope 外） | 不入本期 |
| G10 | 无 Action/全局回调事件系统 | TheRouter ActionManager | 低 | 不入本期 |
| G11 | 拦截器优先级 | ARouter priority | — | ✅ 已实施（批1）：`RouteChainMember.priority`（默认 0 保持顺序，稳定降序），与 L2/L4 并存 |

## C. 结论与建议路线

1. **定位声明**：本项目当前是「高正确性、高可测、真跨进程的**路由导航内核**」，不是「组件化全家桶」；在此定位内我们没有明显短板。
2. 若要向“最优完整方案”看齐，**最小必修 = G7 + G8 + G3**（成本低、收益明确），**可选增益 = G1 + G4 + G6(导出/下发复用 apply)**；**重大决策 = G2 服务层**（是否引入需你拍板，避免再造全家桶包袱）。
3. 顺序建议：先收尾重构批次 C → 实施 P0（G7/G8）与 G3 基线 → 统一回测（覆盖全部场景并把 B 表差距转化为回测对照矩阵）→ 再由你决定 P1/P2。
