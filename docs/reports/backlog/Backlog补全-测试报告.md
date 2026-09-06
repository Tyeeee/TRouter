# TRouter Backlog 补全批 · 测试报告（L1–L4 / F / D 全部完成）

> 状态：**六项全部完成**（L4→L2→L3→F→L1→D）。按新测试协议：每项跑本项专属用例 + 紧邻旧功能回归子集，均绿；
> **统一回测仍待最后集中执行**（你此前安排）。
> 全程约束：凡涉顺序/并发处一律「不可变快照 + 状态迁移驱动 + 串行化」，配可复现该类缺陷的回归用例。

## 各项实施与验证

| 项 | 落地 | 本项/邻接用例 | 结果 |
|---|---|---|---|
| L4 洋葱链 | `WrappingInterceptor`+`InterceptorChain.proceed`+`ChainOutcome`；旧原子拦截器完全兼容 | Onion 6 + 旧拦截器契约/UI 12 | 14/14（run-L4-second） |
| L2 动态增删拦截器 | `addInterceptor/removeInterceptor/registeredInterceptors`；每次 navigate 不可变快照 | Runtime 5 + Onion 6 | 11/11（run-L2-first） |
| L3 目标级拦截器 | `@Interceptor(names)` + KSP `TRouterTargetInterceptorNames` + `bind/unbindTargetInterceptor`；未绑定=明确 Blocked；目标链在全局后 | Target 4 + Runtime 5 | 9/9（run-L3-first） |
| F 热更/持久化 | `applyRouteConfig`（原子：任一冲突整批零落地）+ `export/save/loadDynamicRoutes` + `DynamicRouteCodec` | HotUpdate 4 + V5 Dynamic 4 | 8/8（run-F-second） |
| L1 跨模块编译期检测 | `CrossModuleConflictScanner`(processor JVM main) + Gradle `verifyCrossModuleRouteConflicts`（ksp 后 finalizer） | 正面 6 条清单通过；注入跨模块重复 → 构建失败并列出模块 | l1-negative.log / l1-restored.log |
| D AIDL 动态代理 | `RemoteProxies.delegating`（java Proxy 透明封装 IRouterService），RemoteRouter 统一经代理 | Proxy 1 + Remote 契约 5 | 6/6（run-D-first） |

## 防"顺序/时机依赖"缺陷的落点（逐项）

- L4/L2/L3：navigate 每次取**不可变链快照**；proceed 单次放行守卫；增删只影响下一次（S16-3 等回归锚点）
- F：apply 预校验→零落地原子性；load = 原子替换（先清动态再导入）
- L1：构建期确定性拦截（非运行时机依赖）
- D：透明代理单一扩展点，不改变通道语义（真实跨进程 5 例仍绿佐证）

## 日志文件

`docs/reports/backlog/`：run-L4-first/second、run-L2-first、run-L3-first、run-F-first/second、run-D-first、l1-negative.log、l1-restored.log

## 待办

**统一回测（全量 + 跨进程专项 + 手动走查）**——待你下令执行。
