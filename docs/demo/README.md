# TRouter 测试台 · 导览（V1.0 导航 + V2.0 拦截器）

> 本 demo 是一个**自描述的验收测试台**：任何人无需读代码，就能从界面看懂「每个页面/开关在验证什么、为什么、期望是什么」。
> 场景编号体系：S01–S12（S01–S07 见 `docs/V1.0-测试与演示台规划.md` §2.1；S08–S10 见 `docs/V2.0-拦截器方案.md` §7；S11–S12 见 `docs/V4.0-跨进程路由方案.md` §5）；本文件带你几分钟跑通。

## 一、这是什么

- **TRouter**：自研组件化路由框架。V1.0 = 注解 + KSP + 运行时导航；**V2.0 = 拦截器链**（Block 门禁 / Redirect / Mock）；**V3.0 = 多模块路由聚合**（feature 模块各自产路由，host 聚合）；**V4.0 = 跨进程路由**（@CrossProcess 白名单 + AIDL 通道，由 :remote 进程自己的 TRouter 打开第二进程页面）；**V5.0 = 动态路由 + 路由图谱**（运行时注册/注销、图模型导出）。
- 仓库模块：`:trouter-annotation`（@Route）、`:trouter-processor`（KSP）、`:trouter-core`（api/拦截器/测试底座/页面 UI 工具）、`:app`（host：入口页 + `DemoRouteRegistry` 聚合 + 全部测试）、`:feature-demo`（S01/S02/S09 页面）、`:feature-about`（S03 页面）。页面类仅 navigate 时加载（惰性，跨模块不变）。

## 二、如何运行

```bash
# 1) 起模拟器（AVD trouter_test，API 36；或用你自己的设备）
$ANDROID_HOME/emulator/emulator -avd trouter_test &

# 2) 安装演示 App
./gradlew :app:installDebug

# 3) 跑全部 Instrumentation 用例（31 个 = 历史 26 + V5 5；各里程碑分版验证，统一回测待最后集中执行）
./gradlew :app:connectedDebugAndroidTest
```

打开 App 即进入**场景索引页**：可点击行 = 场景（S 编号 + 说明 + 路径 + 期望）；目标页顶部横幅再次自述；底部「路由表快照」列出当前全部注册路由（**每行尾标 ↦ 来源模块**：host=:app / feature-demo / feature-about，即 V3.0 多模块聚合的可见证据，来自 `TRouter.registeredRoutes()`）。

## 三、场景 → 用例 → 验收对照

| 场景 | 在主页怎么点 | 目标/结果 | 自动化用例 | 验收 |
|---|---|---|---|---|
| S01 | 点「S01 基础页面跳转」 | Second 页（/second · default · Activity） | `MainRouterTest#testBasicNavigation` | R1/O1 |
| S02 | 点「S02 Fragment 目标承载」 | Fragment 演示页（/fragment-demo · FRAGMENT，core 容器承载） | `MainRouterTest#testFragmentNavigation` | R1/O1 |
| S03 | 点「S03 分组路由 · About 页」 | About 页（/about · secondary） | `MainRouterTest#testSecondaryGroupNavigation` | C3/R1/O1 |
| S04 | 点「S04 未注册路径」 | 状态栏降级「未找到路由:/not/exist」+ onLost | `MainRouterTest#testLostNavigation` | R2/O1 |
| S05 | （无需点击）测试注入配置 | isDebug=true 日志输出 / false 静默 | `MainRouterTest#testConfigDebugMode` | R3/T1 |
| S06 | 主页自身 | /main · default · Activity（本页即注册路由） | 启动即覆盖 | R1 |
| S07 | （无 UI）API 契约 | NotInitialized / 重复 install 幂等 / traceId / NotFound+onLost / **V3 聚合唯一性·重复防线** | `TRouterApiContractTest` | 契约分支 |
| S08 | 点「S08 门禁拦截器（Block）」开→ 再点 S01 | /second 被拦截：状态栏「已拦截」+ Blocked，真实页不出现；再关→放行 | `TRouterInterceptorUiTest#testGateBlockThenRelease` | C2/C3 |
| S09 | 点「S09 Mock 拦截器（Redirect）」开→ 再点 S01 | /second 被改写，打开 Mock 页 /mock/second；关→恢复真实页 | `TRouterInterceptorUiTest#testMockRedirectOnThenOff` | C4/C5 |
| S10 | （无 UI）拦截器契约 | 顺序/短路、Redirect 未注册→NotFound、跳数上限防死循环、isDebug=false 拦截仍生效、拦截器异常=Block | `TRouterInterceptorContractTest` | C2/C3/C4 |
| S11 | 点「S11 跨进程导航」 | 请求经 AIDL 发往 :remote 进程，远端 TRouter 打开第二进程页面（页内显示 pid）并异步回传 Success | `MainRouterTest#testRemoteProcessNavigation` | C1/C2/C5 |
| S12 | （无 UI）跨进程契约 | 白名单拒绝未标注路径、服务未配置→Blocked、远端 NotFound 映射、send/recv traceId 互查 | `TRouterRemoteContractTest` | C3/C4/C5 |
| S13 | 点「S13 动态路由 · 注册/注销」再点「S14 导航」 | 运行时注册 /dynamic-demo（页面未标 @Route）→ 打开；注销 → 恢复 NotFound | `TRouterDynamicContractTest` / `MainRouterTest#testDynamicRouteScenario` | V5 C1/C2/C3 |
| S14 | 图谱摘要（主页图景区） | routeGraph() 显示「节点=目标类 · 边=路由」，静态+动态同图 | `TRouterDynamicContractTest#graphIncludesStaticAndDynamicAndStaysConsistent` | V5 C4/O1 |

**返回语义**：`navigate` 采用**同任务导航**（core 经生命周期绑定追踪前台 Activity，在其任务内打开目标；无前台 UI 时才回退 appContext+NEW_TASK）。S01/S02/S03/S09 目标页点「返回」或系统返回键均回到主页（主页 singleTask）。

**运行时反馈**：每个目标页显示「✓ 本次由 TRouter 打开 · path=… · kind=… · traceId=… · 解析 Xms」；直连启动（未走路由）则明示「未带 TRouter 路由元数据」。导航成败另有 Toast + 主页状态栏回显（成功 ✓ / 未找到 / **已拦截**）。页面均做系统栏避让。

## 四、可观测（怎么看日志）

日志统一经 **Timber**，Tag=`TRouter`，消息为结构化 `[节点]` 行（无重复前缀）：

```bash
adb logcat -s TRouter   # 实时跟随；-d 导出已缓冲日志
```

6+2 个关键节点：`[navigate][entry/exit]`（hop=N）、`[GroupLoader][load][start/end]`、`[interceptor][start/eval/end]`（V2.0）、`[remote][send/recv/fail]`（V4.0）。
同一用户点击的 traceId 贯穿整条链（含 Redirect 各跳与跨进程 send/recv 的 origin 互查）。`isDebug=false` 只关日志，**拦截/跨进程行为照常生效**。

## 五、编译期校验（KSP，构建时生效）

- 同 path 重复声明 → 编译错误中断（C1）；
- path 直接写字面量 → Warning「请引用 RouterContract 常量」（C2）；
- 每个 group 生成 `GroupLoader_<Group>` 聚合进 `TRouterGroupRegistry`，页面类仅 navigate 时加载（C3）。

## 六、统一治理对照（设计红线）

统一入口 = `TRouter` 单例；统一配置 = `TRouterConfig`（isDebug/logSink/onLost/**interceptors**）；统一结果 = `TRouterResult` 密封类（Success / NotFound / **Blocked** / NotInitialized，无 null）；统一契约 = `RouterContract` 常量（无硬编码路径）。拦截器为空列表时行为与 V1.0 完全一致。

## 七、完整验收与报告

- V1.0 规划/报告：`docs/V1.0-工程搭建方案.md`、`docs/reports/v1/V1.0-TEST-REPORT.md`
- V2.0 规划：`docs/V2.0-拦截器方案.md`；测试报告：`docs/reports/v2/V2.0-TEST-REPORT.md`
- V3.0 规划：`docs/V3.0-多模块路由聚合方案.md`；测试报告：`docs/reports/v3/V3.0-TEST-REPORT.md`
- V4.0 规划：`docs/V4.0-跨进程路由方案.md`；测试报告：`docs/reports/v4/V4.0-TEST-REPORT.md`
- 版本回顾/backlog：`docs/版本回顾与缺口核查.md`
- 截图：`docs/reports/v2/screenshots/`（09–13）、`docs/reports/v3/screenshots/`（14–15）、`docs/reports/v4/screenshots/`（16 远端页 / 17 主页 S11）。
