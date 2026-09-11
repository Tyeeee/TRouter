# TRouter 待办（backlog）补全批 · 测试报告（L1–L4 / F / D 全部完成）

> **先说人话**：这份报告记录「待办清单里那几项补齐工作」各自做了什么、用什么用例验的、结果如何。
> **六项全部完成**（做下来的顺序是 L4→L2→L3→F→L1→D）。按新测试规矩：每做一项，都跑本项专属用例 + 挨着的旧功能回归用例，结果都是绿；
> **统一回测仍然要等最后集中执行一次**（你之前安排的）。
> 全程守住一条：凡是涉及「先后顺序 / 并发」的地方，一律用「不可变快照 + 状态迁移驱动 + 串行化」——配可复现的回归用例。

**先解释这份报告里的编号和几个词**：

- `L1`～`L4`、`F`、`D`：待办清单里的事项编号，不是功能名。L 是待办项、F 是动态路由热更/持久化、D 是跨进程动态代理。
- `run-*.log` / `l1-*.log`：跑测试时留下的日志文件名，点开就能看全过程。
- **不可变快照**＝每次跳转都先复制一份当时的拦截器列表来用，跑的过程中别人增删都影响不到这一次。
- **串行化**＝让请求排队一个个来，不并发抢资源。
- **原子性**＝要么整批全成功，要么一条都不改。
- **洋葱链**＝拦截器像洋葱一样一层包一层，请求出去要穿过每一层，回来再穿一遍。
- **动态代理**＝运行时自动生成一个「假的接口实现」当中间人，把调用转发过去（这里用来转发跨进程调用）。

## 各项实施与验证

| 项 | 落地 | 本项/邻接用例 | 结果 |
|---|---|---|---|
| L4 洋葱链 | `WrappingInterceptor`+`InterceptorChain.proceed`+`ChainOutcome`；旧的原子拦截器完全兼容 | Onion 6 + 旧拦截器契约/UI 12 | 14/14（run-L4-second） |
| L2 动态增删拦截器 | `addInterceptor/removeInterceptor/registeredInterceptors`；每次 navigate 用一份不可变快照 | Runtime 5 + Onion 6 | 11/11（run-L2-first） |
| L3 目标级拦截器 | `@Interceptor(names)` + KSP 生成 `TRouterTargetInterceptorNames` + `bind/unbindTargetInterceptor`；名字没绑定实现＝明确的 Blocked；目标级链排在全局链之后 | Target 4 + Runtime 5 | 9/9（run-L3-first） |
| F 热更/持久化 | `applyRouteConfig`（原子：只要有一条冲突，整批一条都不落地）+ `export/save/loadDynamicRoutes` + `DynamicRouteCodec` | HotUpdate 4 + V5 Dynamic 4 | 8/8（run-F-second） |
| L1 跨模块编译期检测 | `CrossModuleConflictScanner`(processor JVM main) + Gradle 任务 `verifyCrossModuleRouteConflicts`（挂在 ksp 之后的 finalizer 里跑） | 正面 6 条清单通过；注入跨模块重复 → 构建失败并列出模块 | l1-negative.log / l1-restored.log |
| D AIDL 动态代理 | `RemoteProxies.delegating`（用 java Proxy 把 IRouterService 透明包一层），RemoteRouter 统一走这个代理 | Proxy 1 + Remote 契约 5 | 6/6（run-D-first） |

## 防「顺序/时机依赖」缺陷的落点（逐项）

- L4/L2/L3：每次 navigate 取一份**不可变链快照**；proceed 只允许放行一次（有守卫）；增删只影响下一次跳转（S16-3 等回归锚点）
- F：apply 先预校验 → 保证零落地的原子性；load ＝原子替换（先清掉动态的，再导入）
- L1：在构建期做确定性拦截（不依赖运行时机的先后）
- D：透明代理就这么一个扩展点，不改变通道本身的语义（真实跨进程 5 例仍然绿，可作佐证）

## 日志文件

`docs/reports/backlog/`：run-L4-first/second、run-L2-first、run-L3-first、run-F-first/second、run-D-first、l1-negative.log、l1-restored.log

## 待办

**统一回测（全量 + 跨进程专项 + 手动走查）**——等你下令执行。
