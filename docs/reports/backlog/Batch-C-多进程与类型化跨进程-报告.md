# 批次 C（多进程扩展 + 类型化跨进程）实施与证据报告

> 目标：把跨进程从"单进程对"扩展为**多进程**，并补上类型化（POJO 编解码 + 类型化服务代理），
> 让跨进程调用不再只能靠"Bundle + 字符串协议"手工拼装。
>
> 本报告为**第 1 部分：多进程扩展（已完成）**；第 2 部分（POJO 编解码 / 类型化代理）见文末待办。

---

## 一、第 1 部分：多进程扩展

### 1.1 为什么

原来只有 host + `:remote` 一对。真实工程常见"多个重活进程"（音视频/下载/推送各一份），
而每个进程都有**自己的一份 TRouter 路由表与端点表**——这一点必须被真实验证，而不是靠推理：
本批次直接建**三个进程**跑用例。

### 1.2 core 改动

| 位置 | 变更 |
|---|---|
| `TRouterConfig.remoteServices: Map<String, ComponentName>` | 额外的跨进程目标：key 为逻辑名（示例 `"remote2"`），value 为该进程的 AIDL 服务组件；`target=null` 仍走 `remoteService`（默认进程，向后兼容） |
| `TRouter.navigateRemote(path, bundle, target = null, onResult)` | 新增 `target` 选择目标进程 |
| `TRouter.callRemoteService(name, args, target = null, onResult)` | 同上 |
| 客户端实例管理 | 由"单实例"改为**按目标进程各持一个 `RemoteRouter`**（key = 组件字符串）：连接、队列、重连状态按进程隔离，互不干扰 |
| `RemoteRouterService` | 改为 **open**：同一个 Service 类不能在 manifest 声明两次（不同 `android:process`），因此宿主为每个额外进程声明一个**空子类** |

语义保持（重要）：`target=null` 且 `remoteService` 未配置时，请求**继续下传给 RemoteRouter**，
保持原有的 `Blocked` 文案与 `[remote][fail]` 日志；只有"target 未在 `remoteServices` 里配置"这一新情形
才由框架直接短路（reason 指向 `remoteServices`）。

### 1.3 demo 落地（第三个真实进程）

| 文件 | 内容 |
|---|---|
| `RemoteThirdActivity`（新） | `@Route /remote-third` + `@CrossProcess`，manifest 声明 `android:process=":remote2"`；页面显示**进程名 + pid + 路由证据 + 参数透传证据** |
| `RemoteRouterServiceSecond`（新） | `:remote2` 的 AIDL 服务（core `RemoteRouterService` 的空子类） |
| `app/AndroidManifest.xml` | 新增 `:remote2` 的 activity/service（与既有 `:remote` 并列） |
| `TRouterDemoApp` | `remoteServices = mapOf("remote2" to RemoteRouterServiceSecond)`；按进程注册端点：`:remote` → `demoClock`，`:remote2` → `demoClock2` |
| `MainActivity` | 新增 **S26**（跨进程导航到 `:remote2`）、**S27**（端点按进程隔离：同时调 `:remote2` 与默认 `:remote`，状态栏并列显示两个结果） |

### 1.4 证据

**A. 自动化用例**（新增 `TRouterMultiProcessContractTest`，6 条，真跑三个进程）：

| 用例 | 断言要点 |
|---|---|
| `navigateToThirdProcessOpensPageWithParams` | `target="remote2"` → Success(`/remote-third`)；recv 携带参数回显；send 日志指向 `RemoteRouterServiceSecond` |
| `endpointRegisteredOnlyInThirdProcessIsReachableThere` | `demoClock2` 经 `:remote2` 正常返回（带 pid） |
| `endpointIsInvisibleFromOtherProcess` | `demoClock2` 经**默认 `:remote`** → `-ERR ... unregistered`（端点表按进程独立） |
| `concurrentNavigationToBothProcessesIsIndependent` | 两个进程**并发**导航各自 Success，两条 send 日志分别指向两个不同服务组件 |
| `unconfiguredTargetIsBlocked` | 未配置 target → Blocked，reason 指向 `remoteServices` |
| `whitelistAppliesToExtraProcessToo` | 未标 `@CrossProcess` 的 path 不允许走 `:remote2` |

**B. 人工可见证据**（`docs/reports/backlog/run-batchC-three-processes.log`）：

- 点 S26 → 第三进程页面自述：`本页运行在进程 com.demo.trouter:remote2 · pid=7166`、
  `✓ 本次由 TRouter 打开 · path=/remote-third · traceId=083c1803`、`参数透传 ✓ msg=... count=42`；
- 点 S27 → 状态栏：
  `S27 结果 · :remote2 → clock2-v1 q=from-host pid=7166 ｜ 默认(:remote) → -ERR unregistered:demoClock2`；
- `adb shell ps -A | grep trouter`：
  ```
  com.demo.trouter          6415   (host)
  com.demo.trouter:remote   6485
  com.demo.trouter:remote2  7166
  ```
  三个 pid 互不相同 —— **三进程真实存在**。

**C. 回归**（`run-batchC-remote-regression.log`）：多进程 + 既有跨进程 + 主页场景共 **19/19 通过**。

### 1.5 过程中修正的一处自查问题

第一版把"默认目标未配置"也在 TRouter 里短路，导致既有契约用例失败
（`remoteUnconfiguredReturnsBlockedImmediately` 期望的 `[remote][fail]` 日志没了、
`serviceWithoutRemoteConfiguredReturnsError` 期望的错误串文案变了）。
已改为"默认目标继续下传、只有未知 target 才短路"，既有契约文案与日志完整保留。

---

## 二、第 2 部分（待办）：POJO 编解码 + 类型化远程服务代理

计划实现：

1. **POJO 编解码（KSP 生成，零反射）**：给需要跨进程传的数据类标注解，KSP 生成
   `pack(Bundle, prefix, obj)` / `unpack(Bundle, prefix)`，字段逐个写进 Bundle
   （基础类型 + String + 嵌套 POJO + List），避免手写 `Parcelable`；
2. **类型化远程服务代理**：接口 + 注解 → 客户端侧由 JDK 动态代理（复用既有 `RemoteProxies` 地基）
   把方法调用编码为 AIDL 调用，远端侧由生成的分发器按方法名解包并调用实现；
   方法形态 `fun name(args..., onResult: (Ret) -> Unit)`，参数/返回值支持基础类型、String 与上述 POJO；
3. demo 增加对应场景行与端点；用例覆盖"POJO 跨进程往返一致""类型化接口调用与错误路径"。

完成后执行**统一全量回测**并出总报告。
