# 批次 B（异步拦截器）实施与证据报告

> 目标（来自已确认的三条建议中的第 1 条，**重新定义后**）：不做 DAG 并发调度；
> 补真正的缺口——**允许拦截器等 IO 而不阻塞主线程**。

---

## 一、为什么做这个（而不是 DAG 并发）

- 原论点"拦截器串行导致延迟线性叠加"在工程上站不住：单条拦截器是"读 meta/bundle + 返决策"，
  微秒量级，10~20 条仍是亚毫秒，比一次 `startActivity` 小两个数量级；
- 真正的延迟来源只有一个：**拦截器里要做 IO**（网络风控、定位、读远端配置）。
  而原来的链是同步调用栈 —— 这种拦截器只能阻塞主线程，这是真缺陷；
- 而"同步/异步拦截器"并非空白：WMRouter 的 README 明确写了"可在跳转前执行同步/异步操作"，
  拿 DAG 当差异化会被一句话问倒；
- DAG 并发与 Android 现实冲突（终点必须主线程、决策要汇合、取消要定义、埋点/灰度本质顺序敏感），
  收益 <1ms、风险是正确性 —— 故**不做**。

---

## 二、新增 API（`trouter-core`）

| 类型 | 作用 |
|---|---|
| `AsyncInterceptor` | 异步拦截器：`intercept(chain: AsyncChain)`，可在任意线程、任意时刻结束本轮 |
| `AsyncChain` | 链句柄：`proceed { outcome -> }` / `block(reason)` / `redirect(path)` **三选一、单次有效**；暴露 `meta/bundle/traceId/isCancelled` |
| `RouteRequest` | `navigateAsync` 返回的取消句柄：`cancel()` 幂等 |
| `TRouter.navigateAsync(path, bundle) { result -> }` | 异步导航入口，结果**主线程回调且只回调一次** |
| `TRouterConfig.asyncInterceptorTimeoutMs` | 异步成员单轮终止超时（默认 5000ms） |

### 对外承诺（都有用例守着）

1. 三选一终止，**重复终止抛 `IllegalStateException`**（配置错误显性化）；
2. 超时 → `Blocked`（reason 含超时信息），**迟到的终止静默忽略**（不打开页面、不再回调）；
3. 取消 → `Blocked`（已取消），迟到放行同样被忽略；
4. 异步成员可在任意线程终止，**剩余链与打开目标切回主线程**；
5. 结果回调恒在主线程且**只一次**（超时/取消/正常三选一）；
6. 链中可**连续多个**异步成员（各自独立超时窗口）；
7. 洋葱包裹拦截器**不能跨异步成员**（其 `proceed()` 是同步契约）→ 给出明确 `Blocked`，
   而不是静默错乱；
8. **同步导航兼容规则（关键）**：异步成员**立即放行**时（例如"开关关着就直接放行"）
   同步 `navigate` 照常可用；只有**延迟放行**才 `Blocked` 并提示改用 `navigateAsync`。

---

## 三、实施中由测试抓出的两个真问题（诚实记录）

### 1. 同步模式一律拒绝异步成员 → 误伤跨进程导航

第一版把"链中出现 `AsyncInterceptor`"在同步模式下**一律** `Blocked`。跑回归时
`MainRouterTest#testRemoteProcessNavigation` 失败：`:remote` 进程的 Application 也注入了
同一份全局链（含默认关闭、会立即放行的异步拦截器），于是**远端自己的 `navigate` 被拒绝**，
跨进程导航整条链路失效。

修正为第二节第 8 条的兼容规则：立即放行 = 兼容，延迟放行 = 明确拒绝。
新增用例 `syncNavigateAllowsImmediatelyPassingAsyncMember` 与
`syncNavigateRejectsDeferredAsyncMember` 两条，把该语义锁死。

### 2. 异步改道成环

`asyncRedirectOpensRewrittenTarget` 第一版用例的拦截器无条件改道，跑出
`Blocked(path=/about, reason=redirect loop（>=4 跳）)` —— 说明**防环上限对异步改道同样生效**。
修正用例为"只对 /second 改道"，并**新增** `asyncRedirectLoopIsCapped` 明确覆盖"异步改道成环被截断"。

---

## 四、顺带修掉的既有破损（重要）

批次 B 一开始编译 androidTest 就失败：
`TRouterRemoteProxyTest` 的 fake `IRouterService.Stub` 没有实现批次 3b 新增的 `callService`。

含义必须说清：**自批次 3b 起，androidTest 源码集根本编译不过**，
即那之后"用例全绿"的说法是不成立的（编译失败会让所有用例都跑不了）。
本次已补齐实现，androidTest 恢复可编译；这也解释了为什么本次回归能一次抓出上面两个问题。

---

## 五、环境前提（本次踩到，写进报告避免后人重踩）

新起的模拟器**默认开着窗口动画**，Espresso 会直接拒绝滚动/点击：
`Animations or transitions are enabled on the target device`。
表现为"点击没生效、断言文本不变"，极易误判成代码问题。跑 UI 用例前必须：

```bash
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
```

---

## 六、demo 落地（自描述测试台，S23–S25）

| 场景 | 行为 | 期望 |
|---|---|---|
| **S23** | 切换"异步拦截器开关"（开启后 `/second` 的链里有异步成员，延时 300ms 放行） | 开启后 S01 同步导航 → `Blocked`（reason 提示 navigateAsync）；S24 仍正常 |
| **S24** | `navigateAsync(/second)` | 状态栏 `Success`，提示"回调线程=主线程"，且只回调一次 |
| **S25** | 把异步耗时拉到 3000ms（> 配置超时 1500ms） | `Blocked`，reason 含「异步拦截器超时」，页面不打开；此后迟到放行被忽略 |

实现位置：`app/.../DemoInterceptors.kt`（`AsyncDemoInterceptor`）、`MainActivity.kt`（S23–S25 行与处理）、
`TRouterDemoApp.kt`（`asyncInterceptorTimeoutMs = 1500`）、`res/values/ids.xml`。

---

## 七、证据

新增用例 `app/src/androidTest/kotlin/com/demo/trouter/TRouterAsyncInterceptorTest.kt`（**12 条**）：

| 用例 | 断言要点 |
|---|---|
| `asyncProceedOpensTargetWithSingleMainThreadCallback` | Success + 主线程 + 只回调一次 + 有 `[interceptor][async][wait]` 日志 |
| `asyncBlockReturnsBlockedWithoutOnLost` | Blocked + reason + 不触发 onLost |
| `asyncRedirectOpensRewrittenTarget` | 打开改写后的 `/about` + 有 `result=Redirect` 日志 |
| `asyncRedirectLoopIsCapped` | 异步改道成环 → `redirect loop` Blocked |
| `asyncTimeoutIsBlockedAndLateProceedIgnored` | 超时 Blocked + 迟到放行不再回调（等待 1s 后计数仍为 1） |
| `cancelDeliversBlockedAndLateProceedIgnored` | 取消 Blocked(已取消) + 迟到放行被忽略 + `isCancelled` |
| `duplicateTerminationThrowsIllegalState` | 重复终止抛 `IllegalStateException` |
| `syncNavigateRejectsDeferredAsyncMember` | 延迟放行 → Blocked(提示 navigateAsync) + `deferred-sync` 日志 |
| `syncNavigateAllowsImmediatelyPassingAsyncMember` | 立即放行 → Success（不误伤） |
| `asyncMemberKeepsChainOrder` | 同步在前、异步在后，顺序与后置观察都对 |
| `multipleAsyncMembersBothProceed` | 两个异步成员都能推进（各自超时窗口） |
| `wrappingInterceptorCannotWrapAsyncMember` | 洋葱跨异步 → 明确 Blocked |

运行证据（`docs/reports/backlog/`）：

| 命令范围 | 结果 | 日志 |
|---|---|---|
| `MainRouterTest` + `TRouterAsyncInterceptorTest` | **14/14 通过**（exit 0） | `run-batchB-async-and-mainrouter.log` |
| 受同步引擎影响的既有 5 个套件（洋葱/拦截器契约/运行时增删/目标级/主页场景） | **23/23 通过**（exit 0） | `run-batchB-sync-regression.log` |
| 首次失败现场（保留，用于对照根因） | 动画未关导致 2 例失败 | `run-batchB-async.log`、`run-batchB-sync-regression.log` 早期版本 |

---

## 八、待办

- **批次 C**：类型化跨进程（多进程扩展 + POJO 编解码 + 类型化远程服务代理）+ 多进程用例；
- 之后执行**统一全量回测**并出总报告（含本次发现的"动画必须关闭"前提）。
