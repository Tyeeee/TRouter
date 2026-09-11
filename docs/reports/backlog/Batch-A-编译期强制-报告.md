# 编译期强制改造 实施与证据报告

> **先说人话**：这份报告说的是"**怎么让写错的代码在编译时就报错**"，一共三件事——
> ① 页面路径直接写字符串时，可以配置成"直接编译失败"（默认只是警告）；
> ② 调用点的写死路径（`TRouter.navigate("/second")`）由代码检查工具拦住；
> ③ 两个模块抢同一个路径，构建时自动发现并让构建失败。
>
> 先解释两个词：**"编译期"**＝你写完代码、机器把工程打包成 App 的过程中，人还没运行程序，错误就已经被拦下来；
> **KSP**＝编译期自动生成代码的工具（本项目用它把 `@Route` 注解变成"页面清单"）；
> **Lint**＝Android 官方的代码检查工具，能在构建时按规则挑出问题。
>
> 正文里的 `A1` / `A2` / `A3` 是上面三件事的内部编号，证据日志的文件名里也带着它们
> （例如 `run-enforce-a1-1-warning.log`），这样日志和正文能对上。
> 这一轮改造的内部叫法是"批次 A"，也就是**编译期强制改造**。

> 范围：把"四个统一"（统一入口、统一配置、统一结果、统一契约）里**能被编译期/构建期强制**的部分真正强制起来，不再依赖开发者自觉。
> 这一轮包含三项：A1 把"路径写死"分级，并留一条逃生通道；A2 用自定义 Lint 规则把调用点也管起来；A3 把跨模块路径冲突检查做成插件。

---

## 一、A1：把"路径写死"分成两档，并留一条逃生通道

### 改了什么

| 位置 | 变更 |
|---|---|
| `trouter-annotation/.../Route.kt` | 新增注解参数 `allowLiteral: Boolean = false`（逃生通道：默认关闭，老代码不受影响） |
| `trouter-processor/.../TRouterProcessor.kt` | 新增 KSP 参数 `trouter.pathSeverity`：`warning`（默认，向后兼容）/ `error`（直接中断编译）；不认识的取值不会被悄悄吞掉（先提示，再按 warning 处理） |
| `app` / `feature-demo` / `feature-about` 的 `build.gradle.kts` | `ksp { arg("trouter.pathSeverity", findProperty("trouterPathSeverity") ?: "warning") }`，可以用命令行 `-PtrouterPathSeverity=error` 驱动，方便 CI（每次提交后机器自动跑构建与检查）直接硬卡 |

判定方式没有变（仍然是**读源文件、按括号配对扫描 @Route 注解块、用一段文本匹配规则判断是不是写死的字符串**；读不到源文件就不报，宁可漏报也不误报），
所以把级别升到 `error` 不会引入"引用常量被误判成违规"的假阳性。

### 证据（`docs/reports/backlog/`）

> 表里说的**探针**＝临时故意加的一小段代码，用来确认检查真的会拦住它；验完就删掉。

| 场景 | 结果 | 日志 |
|---|---|---|
| ① 默认级别 | `w: [ksp] @Route path 使用了字符串字面量…` + **BUILD SUCCESSFUL**（exit 0） | `run-enforce-a1-1-warning.log` |
| ② 硬卡级别 `-PtrouterPathSeverity=error` | `e: [ksp] …（已中断编译…）` + **BUILD FAILED**（exit 1） | `run-enforce-a1-2-error.log` |
| ③ 探针改 `allowLiteral = true` + error 级别 | **BUILD SUCCESSFUL**（exit 0）：豁免只作用于本条 | `run-enforce-a1-3-allowliteral.log` |
| ④ 删除探针 + error 级别 | **BUILD SUCCESSFUL**（exit 0）：仓库常驻代码零字面量 | `run-enforce-a1-4-baseline-clean.log` |

---

## 二、A2：用 Lint 管住"调用点写死路径"

### 为什么必须做

KSP 只能看到 `@Route` 注解。调用点的写死路径（`TRouter.navigate("/second")`）它完全看不到——
而"改了路径、忘了改调用方"恰恰是模块化项目里最常见的事故来源。这一层必须由 Lint（Android 官方的代码检查工具）在编译/CI 阶段守住。

### 改了什么

| 位置 | 变更 |
|---|---|
| 新增 `trouter-lint/` 模块 | `com.android.lint` + Kotlin JVM（**JVM 17**：Lint 32.x 自身要求，与 AGP 9.3.2 属同一版本线） |
| `RoutePathLiteralDetector` | 检查项 id `TRouterHardcodedPath`，严重级别 **ERROR**，`Scope.JAVA_FILE_SCOPE`（只看 Java/Kotlin 源码文件） |
| `TRouterIssueRegistry` + `META-INF/services` | 把这条检查项登记进去，Lint 才加载得到它 |
| `app/build.gradle.kts` | `lintChecks(project(":trouter-lint"))` 一行接入 |
| `gradle/libs.versions.toml` | 新增 `lint = "32.3.2"`（AGP 9.3.2 ↔ Lint 32.3.2） |
| `app/.../DemoProcess.kt`（新） | **顺带修掉一个真缺陷**：`Process.myProcessName()` 需要 API 33 而工程 minSdk 24（最低支持 Android 7.0），首次开启 Lint 被 `NewApi` 报出，现统一收口（33+ 官方 API，以下读 `/proc/self/cmdline`） |

判定规则（保守，宁可漏报也不误报）：接收者必须正好是 `com.trouter.core.api.TRouter`；方法名在白名单内
（navigate / navigateForResult / navigateRemote / unregisterRoute / registerRouteAlias）；
实参是**源码里直接写死的字符串**且以 `/` 开头。引用常量、字符串拼接、变量都不报；
确实需要写死的地方，用 `@Suppress("TRouterHardcodedPath")` 可以逐处豁免。

### 实施中踩到并解决的三个坑（都写进用例防回归）

1. **不能用 `getApplicableMethodNames` 过滤**：Kotlin 调用带默认参数的方法时，编译器会另外生成一个合成方法
   `navigate$default`，按方法名过滤会让整条规则整体失效；
2. **Kotlin 的字符串字面量不是 `ULiteralExpression`**：普通字面量在 UAST（Lint 内部表示源码的树形结构）里是
   `KotlinStringTemplateUPolyadicExpression`，只按类型判断会出现"Java 报、Kotlin 不报"——
   本规则第一版就是这样，最终改为按**源码文本**判定（带引号且不含 `$` 才算字面量）；
3. **不能用常量求值 `ConstantEvaluator`**：它会把 `RouterContract.PATH_SECOND` 也求值成 `"/second"`，
   把合规写法误报成违规——而我们要守的恰恰是"引用常量 = 合规"。

### 证据

单元测试（`trouter-lint/src/test/.../RoutePathLiteralDetectorTest.kt`，用的是 Lint 官方测试底座，在电脑上直接跑 JVM，不需要模拟器）**7/7 通过**：

| 用例 | 断言（检查结果是否符合预期） |
|---|---|
| `testHardcodedPathIsReported` | Kotlin 字面量 → 1 条 error，且消息含 issue id 与修复指引 |
| `testKotlinCallWithoutDefaultArgsIsReported` | Kotlin 无默认参数方法（`unregisterRoute`）→ 命中（排除 `$default` 干扰） |
| `testKotlinCallWithExplicitArgsIsReported` | Kotlin 显式传全部实参 → 命中 |
| `testJavaHardcodedPathIsReported` | Java 调用点 → 命中（Java 调用方同样不能漏） |
| `testConstantPathIsClean` | 引用 `RouterContract` 常量 → **零命中**（反向对照，防误报） |
| `testOtherReceiverIsIgnored` | 非 TRouter 的同名方法 → 零命中（防误伤） |
| `testSuppressWorks` | `@Suppress` → 豁免生效 |

真实工程级证据（`:app:lintDebug`，`docs/reports/backlog/`）：

| 场景 | 结果 | 日志 |
|---|---|---|
| ① 调用点硬编码（Kotlin） | **BUILD FAILED**（exit 1），报告命中 `LintProbe.kt` 第 8 行**仅 1 处**（第 9 行的常量调用未报 = 无误报） | `run-enforce-a2-1-hardcoded.log` |
| ② 同一处加 `@Suppress` | **BUILD SUCCESSFUL**（exit 0） | `run-enforce-a2-2-suppressed.log` |
| ③ 删除探针后的仓库基线 | **BUILD SUCCESSFUL**（exit 0，原先的 `NewApi` 错误已随修复消失） | `run-enforce-a2-3-restored.log`、`run-enforce-a2-0-baseline.log` |

---

## 三、A3：把跨模块路径冲突检查做成插件

### 改了什么

| 位置 | 变更 |
|---|---|
| 新增 `trouter-gradle-plugin/`（独立构建，`includeBuild` 接入） | 插件 id `com.trouter.route-conflict`：注册任务 `verifyTRouterRoutes` |
| 插件实现 | 自动发现**所有跑过 KSP 的子工程**、扫描其 `build/generated/ksp/<variant>/kotlin` 下的 `GroupLoader_*.kt`，同一 path 出现在 ≥2 个模块 → 构建失败；并自动把这些模块的 `ksp<Variant>Kotlin` 作为依赖，挂到 `check` 与 `assemble*`（`trouterConflict { variants / autoWire / modules }` 可配） |
| `app/build.gradle.kts` | 删除原 JavaExec + `finalizedBy` 手工接线（原来是自己写一段脚本、手工把检查任务挂到构建流程上），改为 `plugins { id("com.trouter.route-conflict") }` 一行 |
| `trouter-processor/.../CrossModuleConflictScanner.kt` | **删除**（逻辑收敛进插件，消除重复实现） |

收益：接入方不再需要照抄脚本与目录清单，"忘记接线""目录写死""模块漏配"三类人为错误同时消失。

### 证据（`docs/reports/backlog/`）

| 场景 | 结果 | 日志 |
|---|---|---|
| ① 正常工程 | `TRouter 跨模块校验通过：无重复 path（扫描模块 3 个，路由清单项 7 条）` + exit 0 | `run-enforce-a3-4-final.log` |
| ② 人为制造跨模块冲突（`feature-about` 抢 `feature-demo` 的 `/second`） | `TRouter 跨模块路由 path 冲突：/second ← :feature-about(GroupLoader_default.kt) / :feature-demo(GroupLoader_default.kt)` + **BUILD FAILED**（exit 1） | `run-enforce-a3-1-conflict.log` |
| ③ 删除冲突探针后 | **BUILD SUCCESSFUL**（exit 0），日志含 KSP 失效判定与清理明细 | `run-enforce-a3-2-restored.log` |
| ④ 自动挂载 | `:app:assembleDebug --dry-run` 任务清单包含 `verifyTRouterRoutes` | `run-enforce-a3-3-autowire.log` |

---

## 四、过程中的一个诚实记录

采集中出现过一次"删除探针后闸门仍报冲突"。（这里说的**闸门**＝一道自动检查关卡，不通过就中断构建。）
原因是**生成物过期**——该轮 KSP 任务被判 UP-TO-DATE
（Gradle 认为这一步的输入没变，于是跳过不重跑），沿用上一次生成的路由清单（含已删除的探针路径）。
随后做了 3 次复现实验（含 2 轮"秒级建删"极端时序），
KSP 均正确检测到源文件移除（`Input property 'sources' … has been removed`）并清理旧生成文件，**未再复现**。

处理方式（不掩盖）：
1. 闸门的冲突提示里加入排障指引——“若刚删除过页面/路由，请先 `--rerun-tasks` 或 `clean` 排除生成物过期导致的误报”；
2. 本报告留档，作为后续观察项。

---

## 五、这一轮之后的待办

- **下一轮（内部编号"批次 B"）**：异步拦截器（`navigateAsync` / 单次 proceed / 超时 / 取消）+ demo 场景与用例；
- **再下一轮（内部编号"批次 C"）**：类型化跨进程（多进程扩展 + 对象打包还原的 POJO 编解码 + 类型化远程服务代理）+ 多进程用例；
- 全部完成后执行**统一全量回测**并出报告。
