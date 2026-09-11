# TRouter 演示 App · 导览（V1.0 导航 + V2.0 拦截器）

> **先说人话**：这个演示 App 是一个「自己会解释自己」的验收工具。你不用读代码，照着界面从上往下点，就能看懂每一行在验证什么、为什么这么验证、应该看到什么结果。
> 场景编号体系：S01–S12，就是首页从上往下的一行行编号，每段的出处是——S01–S07 见 `docs/V1.0-测试与演示台规划.md` §2.1；S08–S10 见 `docs/V2.0-拦截器方案.md` §7；S11–S12 见 `docs/V4.0-跨进程路由方案.md` §5。
> 这份文件带你几分钟跑通。
>
> **先解释几个词**：`V1.0`～`V5.0` 是项目分五个阶段做的版本号，数字越大做得越晚；`KSP`＝编译期自动生成代码的工具；`AIDL`＝Android 提供的进程间通信方式；`Bundle`＝Android 用来装参数的「袋子」；`Mock`＝假装／模拟（这里指用一个假开关把页面换掉，用来演示灰度或兜底）。

## 一、这是什么

- **TRouter**：自己写的组件化路由框架（组件化＝把 App 拆成多个模块）。各阶段分别做了什么：
  - **V1.0 = 注解 + KSP + 运行时跳转**；
  - **V2.0 = 拦截器链**（拦下 Block／改道 Redirect／Mock 假换页）；
  - **V3.0 = 多模块路由聚合**（每个 feature 模块各自产出自己的路由表，由 host 汇总起来）；
  - **V4.0 = 跨进程路由**（@CrossProcess 白名单 + AIDL 通道，由 :remote 进程自己的 TRouter 去打开第二进程里的页面）；
  - **V5.0 = 动态路由 + 路由图谱**（运行时注册/注销路径、把路由关系导出成图）。
- 仓库模块：`:trouter-annotation`（放 @Route 注解）、`:trouter-processor`（KSP 编译期生成代码）、`:trouter-core`（对外接口/拦截器/测试底座/页面 UI 工具）、`:app`（host：入口页 + `DemoRouteRegistry` 汇总 + 全部测试）、`:feature-demo`（S01/S02/S09 页面）、`:feature-about`（S03 页面）。页面类只在真正要跳转时才加载（这叫惰性加载，跨模块也一样）。

## 二、如何运行

```bash
# 1) 起模拟器（AVD trouter_test，API 36；或用你自己的设备）
$ANDROID_HOME/emulator/emulator -avd trouter_test &

# 2) 安装演示 App
./gradlew :app:installDebug

# 3) 跑全部 Instrumentation 用例（31 个 = 历史 26 + V5 5；各里程碑分版验证，统一回测待最后集中执行）
./gradlew :app:connectedDebugAndroidTest
```

打开 App 就进入**场景索引页**：能点的行就是一个场景（含 S 编号 + 说明 + 路径 + 期望结果）；目标页顶部会再自述一遍；页面底部「路由表快照」列出当前注册的全部路由（**每行末尾标 ↦ 来源模块**：host=:app / feature-demo / feature-about，这就是 V3.0 多模块聚合看得见的证据，数据来自 `TRouter.registeredRoutes()`）。

## 三、场景 → 用例 → 验收对照

> 读法：第一列是场景编号，第二列是在首页怎么点，第三列点完会看到什么，第四列是自动化的用例名，第五列是当初的验收项编号。

| 场景 | 在主页怎么点 | 目标/结果 | 自动化用例 | 验收 |
|---|---|---|---|---|
| S01 | 点「S01 打开一个新页面」 | Second 页（/second · default · Activity） | `MainRouterTest#testBasicNavigation` | R1/O1 |
| S02 | 点「S02 打开一个「页面片段」（Fragment）」 | Fragment 演示页（/fragment-demo · FRAGMENT，由 core 的容器页承载） | `MainRouterTest#testFragmentNavigation` | R1/O1 |
| S03 | 点「S03 打开另一个模块里的页面」 | About 页（/about · secondary） | `MainRouterTest#testSecondaryGroupNavigation` | C3/R1/O1 |
| S04 | 点「S04 打开一个不存在的页面（看兜底提示）」 | 状态栏给出兜底提示「未找到路由:/not/exist」+ 触发 onLost | `MainRouterTest#testLostNavigation` | R2/O1 |
| S05 | （无需点击）测试注入配置 | isDebug=true 日志输出 / false 静默 | `MainRouterTest#testConfigDebugMode` | R3/T1 |
| S06 | 主页自身 | /main · default · Activity（本页即注册路由） | 启动即覆盖 | R1 |
| S07 | （无 UI）API 契约 | NotInitialized / 重复 install 幂等 / traceId / NotFound+onLost / **V3 聚合唯一性·重复防线** | `TRouterApiContractTest` | 契约分支 |
| S08 | 点「S08 开关：把某个页面临时拦住（像登录校验）」开启 → 再点 S01 | /second 被拦截：状态栏「已拦截」+ Blocked，真实页不出现；关掉开关 → 放行 | `TRouterInterceptorUiTest#testGateBlockThenRelease` | C2/C3 |
| S09 | 点「S09 开关：把一个页面换成另一个页面（灰度/Mock）」开启 → 再点 S01 | /second 被改写成 Mock 页 /mock/second；关掉 → 恢复真实页 | `TRouterInterceptorUiTest#testMockRedirectOnThenOff` | C4/C5 |
| S10 | （无 UI）拦截器契约 | 顺序/短路、Redirect 未注册→NotFound、跳数上限防死循环、isDebug=false 拦截仍生效、拦截器异常=Block | `TRouterInterceptorContractTest` | C2/C3/C4 |
| S11 | 点「S11 把页面开在第二个进程里」 | 请求经 AIDL 发往 :remote 进程，那边的 TRouter 打开第二进程的页面（页内显示 pid），再异步回传 Success | `MainRouterTest#testRemoteProcessNavigation` | C1/C2/C5 |
| S12 | （无 UI）跨进程契约 | 白名单拒绝未标注路径、服务未配置→Blocked、远端 NotFound 映射、send/recv 的 traceId 互查 | `TRouterRemoteContractTest` | C3/C4/C5 |
| S13 | 点「S13 开关：运行时注册 / 注销一条路径」，再点「S14 打开刚注册的那条路径」 | 运行时注册 /dynamic-demo（该页面没有标 @Route）→ 能打开；注销 → 恢复 NotFound | `TRouterDynamicContractTest` / `MainRouterTest#testDynamicRouteScenario` | V5 C1/C2/C3 |
| S14 | 无需点击：看主页底部的「图谱摘要」区 | routeGraph() 显示「节点=目标类 · 边=路由」，静态路由和动态路由在同一张图里 | `TRouterDynamicContractTest#graphIncludesStaticAndDynamicAndStaysConsistent` | V5 C4/O1 |
| S19 | 点「S19 带参数跳转 → Activity 页」 | Second 页收到两个参数（一段文字 + 一个数字），页面上显示「参数透传 ✓」 | `TRouterParamPassingTest` | 后续新增 |
| S20 | 点「S20 带参数跳转 → Fragment 页」 | Fragment 页显示收到的两个参数 | `TRouterParamPassingTest` | 后续新增 |
| S21 | 点「S21 带参数跳到第二个进程的页面」 | 参数经跨进程通道送到第二个进程，页面上显示收到的参数和自己的进程号 | `TRouterParamPassingTest` | 后续新增 |
| S22 | 点「S22 打开页面，并在它返回时拿到数据」 | 打开结果页 → 点页面里的「返回并携带结果」→ 主页状态栏显示收到的数据 | `TRouterResultAndArgsTest` | 后续新增 |
| S23 | 点「S23 开关：跳转前先「等一会儿」（模拟联网检查）」 | 打开开关后，用**普通方式**跳转会被明确拒绝，并提示改用异步方式（这是设计如此，不是坏了） | `TRouterAsyncInterceptorTest` | 后续新增 |
| S24 | 点「S24 等待检查完成后再打开页面」 | 异步跳转成功；状态栏显示结果，并提示「回调线程=主线程」 | `TRouterAsyncInterceptorTest` | 后续新增 |
| S25 | 点「S25 检查一直没结果会怎样（超时保护）」 | 超过设定的等待时间 → 返回「超时」，页面**不会**打开；此后拦截器迟到的放行会被忽略 | `TRouterAsyncInterceptorTest` | 后续新增 |
| S26 | 点「S26 把页面开在第三个进程里」 | 页面自述运行在第三个进程，进程号与主进程、第二个进程都不同 | `TRouterMultiProcessContractTest` | 后续新增 |
| S27 | 点「S27 两个进程各有自己的接口，互不串台」 | 状态栏并列显示两个结果：第三个进程正常返回 ｜ 默认进程返回「未注册」 | `TRouterMultiProcessContractTest` | 后续新增 |
| S28 | 点「S28 跨进程传一个业务对象（不用手写序列化）」 | 业务对象经跨进程通道送达第三个进程，被解包后逐个字段回显 | `TRouterPojoCrossProcessTest` | 后续新增 |
| S29 | 点「S29 像调本地接口一样，调另一个进程里的接口」 | 连续三次跨进程接口调用都拿到正确结果（含枚举、列表、业务对象），并带上远端进程号 | `TRouterTypedApiCrossProcessTest` | 后续新增 |

**返回语义**：`navigate` 走的是**同任务导航**（core 通过生命周期绑定盯住当前前台的 Activity，在它所在的任务里打开目标页；只有确实没有前台界面时，才退回到 appContext + NEW_TASK）。S01/S02/S03/S09 的目标页，点页面里的「返回」或按系统返回键，都会回到主页（主页是 singleTask）。

**运行时反馈**：每个目标页都会显示「✓ 本次由 TRouter 打开 · path=… · kind=… · traceId=… · 解析 Xms」；如果是直连启动、没走路由，就明确显示「未带 TRouter 路由元数据」。跳转成功与否还另有 Toast + 主页状态栏回显（成功 ✓ / 未找到 / **已拦截**）。所有页面都做了系统栏避让。

## 四、可观测（怎么看日志）

日志统一走 **Timber**（一个 Android 日志库），Tag=`TRouter`，每行都是结构化的 `[节点]` 形式（不会重复加前缀）：

```bash
adb logcat -s TRouter   # 实时跟随；-d 导出已缓冲日志
```

6+2 个关键节点：`[navigate][entry/exit]`（hop=N）、`[GroupLoader][load][start/end]`、`[interceptor][start/eval/end]`（V2.0）、`[remote][send/recv/fail]`（V4.0）。
同一次用户点击的日志都带同一个 traceId（链路编号），从入口一直贯穿到 Redirect 的每一跳、以及跨进程 send/recv 的 origin 互查。`isDebug=false` 只是不打日志，**拦截和跨进程的行为照常生效**。

## 五、编译期 / 构建期自动检查

这些检查是"写错了构建就不过"，不靠人工评审：

- 同一个路径在同一个模块里重复声明 → **编译报错**，构建中断；
- 注解里把路径写成死字符串 → **警告**；想彻底禁止，加 `-PtrouterPathSeverity=error` 就变成编译错误（个别确实要写死的用 `@Route(..., allowLiteral = true)` 声明）；
- **调用点**把路径写成死字符串（`TRouter.navigate("/second")`）→ 由代码检查规则 `TRouterHardcodedPath` 拦下，构建失败；
- **两个模块抢同一个路径** → 由 Gradle 插件 `com.trouter.route-conflict` 在构建期发现，构建失败；
- 每个分组生成一份 `GroupLoader_<分组名>`，汇总进 `TRouterGroupRegistry`；页面类只在真正跳转时才加载。

## 六、统一治理对照（设计红线）

统一入口 = `TRouter` 单例；统一配置 = `TRouterConfig`（isDebug/logSink/onLost/**interceptors**）；统一结果 = `TRouterResult` 密封类（密封类＝所有可能结果都列全了，Success / NotFound / **Blocked** / NotInitialized，永远不会是 null）；统一契约 = `RouterContract` 常量（不写死路径）。拦截器列表为空时，行为与 V1.0 完全一致。

## 七、完整验收与报告

- V1.0 规划/报告：`docs/V1.0-工程搭建方案.md`、`docs/reports/v1/V1.0-TEST-REPORT.md`
- V2.0 规划：`docs/V2.0-拦截器方案.md`；测试报告：`docs/reports/v2/V2.0-TEST-REPORT.md`
- V3.0 规划：`docs/V3.0-多模块路由聚合方案.md`；测试报告：`docs/reports/v3/V3.0-TEST-REPORT.md`
- V4.0 规划：`docs/V4.0-跨进程路由方案.md`；测试报告：`docs/reports/v4/V4.0-TEST-REPORT.md`
- 后续三批改造（编译期强制 / 异步拦截器 / 多进程与跨进程增强）的报告：`docs/reports/backlog/Batch-A-编译期强制-报告.md`、`Batch-B-异步拦截器-报告.md`、`Batch-C-多进程与类型化跨进程-报告.md`
- **全量回测结果（68 个功能节点 + 109 个设备用例，含发现并修掉的缺陷）：`docs/reports/backtest/回测台-功能节点回测报告.md`**
- 上一轮（回测台之前）的结果留档：`docs/reports/final/最终回测报告.md`
- 版本回顾 / 待办清单：`docs/版本回顾与缺口核查.md`
- 截图：仓库里只保留了 V1.0 那一组 `docs/reports/v1/screenshots/`（01–08）；V2.0 之后的截图当时没有纳入版本库，对应报告里的 `screenshots/16-…png` 之类引用取不到文件。
