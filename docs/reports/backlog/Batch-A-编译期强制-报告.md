# 批次 A（编译期强制）实施与证据报告 · 第 1 部分

> 范围：把"四个统一"里**能被编译期/构建期强制**的部分真正强制起来。
> 本部分完成 A1（KSP 路径字面量分级 + 逃生通道）与 A3（跨模块冲突校验插件化）；
> A2（自定义 Lint 规则覆盖调用点）在下一部分实施。

---

## 一、A1：路径字面量分级 + 逃生通道

### 改了什么

| 位置 | 变更 |
|---|---|
| `trouter-annotation/.../Route.kt` | 新增注解参数 `allowLiteral: Boolean = false`（免责通道，默认关闭，向后兼容） |
| `trouter-processor/.../TRouterProcessor.kt` | 新增 KSP 参数 `trouter.pathSeverity`：`warning`（默认，向后兼容）/ `error`（编译中断）；未知识别值不静默吞掉（提示后按 warning 处理） |
| `app` / `feature-demo` / `feature-about` 的 `build.gradle.kts` | `ksp { arg("trouter.pathSeverity", findProperty("trouterPathSeverity") ?: "warning") }` —— 可用命令行 `-PtrouterPathSeverity=error` 驱动，便于 CI 直接硬卡 |

判定方式未变（仍是**读源文件、括号平衡扫描 @Route 注解块、正则判字面量**，读不到源文件就不报，宁缺勿误报），
因此升级到 `error` 不会引入"常量引用被误判"的假阳性。

### 证据（`docs/reports/backlog/`）

| 场景 | 命令 | 结果 | 日志 |
|---|---|---|---|
| ① 默认级别 | `:feature-demo:kspDebugKotlin` | `w: [ksp] @Route path 使用了字符串字面量…` + **BUILD SUCCESSFUL**（exit 0） | `run-enforce-a1-1-warning.log` |
| ② 硬卡级别 | 同上 + `-PtrouterPathSeverity=error` | `e: [ksp] …（当前 trouter.pathSeverity=error，已中断编译…）` + **BUILD FAILED**（exit 1） | `run-enforce-a1-2-error.log` |
| ③ 逃生通道 | 探针改为 `@Route(path = "…", allowLiteral = true)` + error 级别 | **BUILD SUCCESSFUL**（exit 0）：豁免只作用于本条 | `run-enforce-a1-3-allowliteral.log` |
| ④ 基线干净 | 删除探针 + error 级别 | **BUILD SUCCESSFUL**（exit 0）：仓库常驻代码零字面量 | `run-enforce-a1-4-baseline-clean.log` |

探针文件为临时文件，四次采集后已删除，**未进入仓库**。

---

## 二、A3：跨模块冲突校验插件化

### 改了什么

| 位置 | 变更 |
|---|---|
| 新增 `trouter-gradle-plugin/`（独立构建，`includeBuild` 接入） | 插件 id `com.trouter.route-conflict`：注册任务 `verifyTRouterRoutes` |
| 插件实现 | 自动发现**所有跑过 KSP 的子工程**、扫描其 `build/generated/ksp/<variant>/kotlin` 下的 `GroupLoader_*.kt`，同一 path 出现在 ≥2 个模块 → 构建失败；并自动把这些模块的 `ksp<Variant>Kotlin` 作为依赖，挂到 `check` 与 `assemble*`（`trouterConflict { variants / autoWire / modules }` 可配） |
| `app/build.gradle.kts` | 删除原 JavaExec + `finalizedBy` 手工接线，改为 `plugins { id("com.trouter.route-conflict") }` 一行 |
| `trouter-processor/.../CrossModuleConflictScanner.kt` | **删除**（逻辑收敛进插件，消除重复实现） |

收益（针对"依赖开发者自觉"的问题）：接入方不再需要照抄一段脚本与目录清单——
一行插件、自动发现、自动挂载，忘记接线的可能性被消除；目录写死、模块漏配这两类人为错误同时消失。

### 证据（`docs/reports/backlog/`）

| 场景 | 结果 | 日志 |
|---|---|---|
| ① 正常工程 | `TRouter 跨模块校验通过：无重复 path（扫描模块 3 个，路由清单项 7 条）` + exit 0 | `run-enforce-a3-4-final.log` |
| ② 人为制造跨模块冲突（`feature-about` 抢 `feature-demo` 的 `/second`） | `TRouter 跨模块路由 path 冲突：/second ← :feature-about(GroupLoader_default.kt) / :feature-demo(GroupLoader_default.kt)` + **BUILD FAILED**（exit 1） | `run-enforce-a3-1-conflict.log` |
| ③ 删除冲突探针后 | **BUILD SUCCESSFUL**（exit 0），日志含 KSP 失效判定与清理明细 | `run-enforce-a3-2-restored.log` |
| ④ 自动挂载 | `:app:assembleDebug --dry-run` 任务清单包含 `verifyTRouterRoutes` | `run-enforce-a3-3-autowire.log` |

---

## 三、过程中的一个诚实记录

采集中出现过一次"删除探针后闸门仍报冲突"：原因是**生成物过期**——该轮 KSP 任务被判 UP-TO-DATE，
沿用上一次生成的路由清单（含已删除的探针路径）。随后做了 3 次复现实验（含 2 轮"秒级建删"极端时序），
KSP 均正确检测到源文件移除（`Input property 'sources' … has been removed`）并清理了旧生成文件，**未再复现**。

处理方式（不掩盖）：
1. 闸门的冲突提示里加入排障指引——"若刚删除过页面/路由，请先 `--rerun-tasks` 或 `clean` 排除生成物过期导致的误报"；
2. 在本报告留档，作为后续观察项（若再次出现，说明 KSP/Gradle 文件监听存在漏检，需要给闸门加"生成物新鲜度"校验）。

---

## 四、待办（本批次第 2 部分与后续批次）

- **A2**：自定义 Lint 规则，覆盖**调用点**硬编码（`TRouter.navigate("/second")`）——这是当前"四个统一"真正的漏洞，
  KSP 看不到函数调用；
- **批次 B**：异步拦截器（`navigateAsync` / 单次 proceed / 超时 / 取消）；
- **批次 C**：类型化跨进程（多进程扩展 + POJO 编解码 + 类型化远程服务代理）；
- 全部完成后执行统一全量回测并出报告。
