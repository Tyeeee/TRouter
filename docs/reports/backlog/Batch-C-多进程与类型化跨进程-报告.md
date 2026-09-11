# 多进程与跨进程增强 实施与证据报告

> **先说人话**：这份报告说的是两件事——
> ① **多进程**：把页面开在第二个、第三个独立进程里，每个进程有自己的路径表和接口表（演示工程真的开了三个进程来验证）；
> ② **跨进程传数据更省事**：给数据类加个注解就能跨进程传对象（不用手写序列化代码），
>    给接口加个注解就能"像调本地接口一样"调另一个进程里的方法。
>
> 几个词先解释清楚：
> - **进程**＝一个 App 可以拆成几个"独立运行的小房子"，互不影响（一个崩了不至于全崩）；**跨进程**＝在不同的小房子之间通信；
> - **注解**＝写在代码上的标记，比如 `@RemotePojo`，用来告诉编译期工具"这个类归我管"；
> - **序列化**＝把一个对象变成"能传输的数据"的过程；Android 传统做法要手写一大堆代码，本项目交给编译期工具自动生成；**POJO**＝普通的业务数据类；
> - **Bundle**＝Android 自带的"参数袋子"，页面之间、进程之间传参数都用它；
> - **编解码**＝把对象打包成能传输的数据、到了对面再还原回来，本项目里也叫打包/还原；
> - **AIDL**＝Android 提供的进程间通信方式，本项目内部用它传请求和结果。
>
> 这一轮改造的内部编号是"批次 C"；正文里的小节号（如 1.1、2.1）只是章节编号，方便在文内定位。

> 目标：把跨进程从"单进程对"扩展为**多进程**，并补上类型化（对象打包还原的 POJO 编解码 + 类型化服务代理），
> 让跨进程调用不再只能靠"Bundle + 字符串协议"手工拼装。
> （这里的**类型化**＝调用双方都用真实的类和接口来写代码，不用自己拼字符串、自己拆字段。）

> 本报告先写**第 1 部分：多进程扩展**，后面两节接着写**第 2 部分：对象打包还原（POJO 编解码）与类型化远程服务代理**——
> 这两部分的小节标题都标注了"已完成"；真正的收尾待办在文末。

---

## 一、第 1 部分：多进程扩展

### 1.1 为什么

原来只有 host + `:remote` 一对。真实工程常见"多个重活进程"（音视频/下载/推送各一份），
而每个进程都有**自己的一份 TRouter 路由表与端点表**（端点表＝这个进程登记了哪些可以被远程调用的接口）——这一点必须被真实验证，而不是靠推理：
这一轮直接建**三个进程**跑用例。

### 1.2 core 改动

| 位置 | 变更 |
|---|---|
| `TRouterConfig.remoteServices: Map<String, ComponentName>` | 额外的跨进程目标：key 为逻辑名（示例 `"remote2"`），value 为该进程的 AIDL 服务组件；`target=null` 仍走 `remoteService`（默认进程，向后兼容） |
| `TRouter.navigateRemote(path, bundle, target = null, onResult)` | 新增 `target` 选择目标进程 |
| `TRouter.callRemoteService(name, args, target = null, onResult)` | 同上 |
| 客户端实例管理 | 由"单实例"改为**按目标进程各持一个 `RemoteRouter`**（key = 组件字符串）：连接、队列、重连状态按进程隔离，互不干扰 |
| `RemoteRouterService` | 改为 **open**：同一个 Service 类不能在 manifest（`AndroidManifest.xml`，App 的清单文件）声明两次（不同 `android:process`），因此宿主为每个额外进程声明一个**空子类** |

语义保持（重要）：`target=null` 且 `remoteService` 未配置时，请求**继续下传给 RemoteRouter**，
保持原有的 `Blocked` 文案与 `[remote][fail]` 日志；只有"target 未在 `remoteServices` 里配置"这一新情形
才由框架直接短路（reason 指向 `remoteServices`）。

### 1.3 demo 落地（第三个真实进程）

| 文件 | 内容 |
|---|---|
| `RemoteThirdActivity`（新） | `@Route /remote-third` + `@CrossProcess`，manifest 声明 `android:process=":remote2"`；页面显示**进程名 + pid + 路由证据 + 参数透传证据**（透传＝参数原样传到对面） |
| `RemoteRouterServiceSecond`（新） | `:remote2` 的 AIDL 服务（core `RemoteRouterService` 的空子类） |
| `app/AndroidManifest.xml` | 新增 `:remote2` 的 activity/service（与既有 `:remote` 并列） |
| `TRouterDemoApp` | `remoteServices = mapOf("remote2" to RemoteRouterServiceSecond)`；按进程注册端点：`:remote` → `demoClock`，`:remote2` → `demoClock2` |
| `MainActivity` | 新增 **S26**（跨进程导航到 `:remote2`）、**S27**（端点按进程隔离：同时调 `:remote2` 与默认 `:remote`，状态栏并列显示两个结果） |

### 1.4 证据

**A. 自动化用例**（新增 `TRouterMultiProcessContractTest`，6 条，真跑三个进程）：

| 用例 | 断言要点（断言＝检查结果是否符合预期） |
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

第一版把"默认目标未配置"也在 TRouter 里短路（短路＝提前返回、不再往下走），导致既有契约用例失败
（`remoteUnconfiguredReturnsBlockedImmediately` 期望的 `[remote][fail]` 日志没了、
`serviceWithoutRemoteConfiguredReturnsError` 期望的错误串文案变了）。
已改为"默认目标继续下传、只有未知 target 才短路"，既有契约文案与日志完整保留。

---

## 二、第 2 部分 · 2.1 跨进程传对象：给数据类加个注解（已完成）

### 2.1.1 做了什么

| 位置 | 内容 |
|---|---|
| `@RemotePojo`（annotation） | 标注需要跨进程传输的数据类 |
| `RemotePojoEmitter`（processor，新） | 为每个标注类生成 `TRouterPojo_<类名>`：不靠反射（反射＝运行期按名字去找类和方法，慢且容易出错）、逐字段写 Bundle；另生成模块级 `TRouterPojoRegistry`（按类名取打包/还原代码，供框架侧使用） |
| `TRouterPojoCodec`（core api，新） | 通用编解码契约（`className` / `prefix` / `pack` / `unpack`），是后续"类型化代理"的框架侧入口 |
| demo | `DemoReport` / `DemoInner` / `DemoLevel`（含 String、Int、Boolean、`List<String>`、枚举、**可空嵌套 POJO**）；`:remote2` 注册 `pojoEcho` 端点，把收到的对象还原后回显；主页新增 **S28** |

支持类型白名单（允许名单）：String / Int / Long / Float / Double / Boolean（可空亦可）、枚举（按 name）、
`List<String>` / `List<Int>` / `List<Long>`、**同模块**内另一个 `@RemotePojo`。
可空字段用 `<key>.__null` 标志位表达 null（Bundle 无法区分"没写"与"写了 null"）。
白名单之外的类型**直接编译报错**，不做静默降级（不会悄悄换个写法糊过去）；跨模块嵌套也明确报错并说明原因。

### 2.1.2 证据

| 证据 | 结果 | 日志 |
|---|---|---|
| 用例 `TRouterPojoCrossProcessTest`（4 条） | **4/4 通过**：本地往返相等、null 语义、注册表通用接口、**真实跨进程**（`:remote2` 解包回显 id/count/ok/tags/inner/level + pid） | `run-batchC-pojo.log` |
| 负向编译闸门（闸门＝一道自动检查关卡，不通过就中断构建；这里专门写一个反面例子来验证它会拦住，探针用 `java.util.Date` 字段） | `e: [ksp] @RemotePojo 字段类型不受支持：… java.util.Date …支持：…` + **BUILD FAILED** | `run-batchC-pojo-negative.log` |
| 删除探针后 | **BUILD SUCCESSFUL** | `run-batchC-pojo-restored.log` |

### 2.1.3 生成器第一版的两个真问题（已修）

1. **枚举字段缺少 import**：生成文件只 import 了 POJO 自身，`DemoLevel.valueOf(...)` 无法解析 → 补齐枚举 import；
2. **接口实现冲突**：默认前缀的 `unpack(bundle)` 与 `TRouterPojoCodec.unpack(bundle): Any` 同时声明导致
   "Conflicting overloads" → 改为让默认前缀版本**直接作为接口实现（返回类型协变：实现方法可以返回接口声明类型的子类型）**，删掉重复声明。

（KSP 只生成代码、不编译生成代码，所以这两处只有在 `:app:compileDebugKotlin` 阶段才暴露 —— 生成器改动后必须编译整模块，不能只看 KSP 成功。）

## 三、第 2 部分 · 2.2 类型化远程服务代理（已完成）

### 2.2.1 做了什么

| 位置 | 内容 |
|---|---|
| `@RemoteApi`（annotation） | 标注接口；方法约定：最后一个参数是 `(T) -> Unit` 回调，其余参数与 T 走统一类型白名单 |
| `RemoteTypeClassifier`（processor，新） | `@RemotePojo` 字段与 `@RemoteApi` 参数/返回值**共用同一套类型判定**（不再两套白名单各说各话） |
| `RemoteApiEmitter`（processor，新） | 为接口生成 `TRouterRemoteApi_<接口名>`：方法编解码器、`registerClient()`、`register(impl)`（按方法名分发，在 binder 线程上等待实现回调，超时 5s）；另生成 `TRouterRemoteApiRegistry`。binder 线程＝系统用于进程间通信的工作线程，不是主线程 |
| `IRouterService.callTyped(...)`（AIDL） | 类型化通道：入参与结果都走**原生 Bundle**，不做字符串二次编码 |
| `TRouterRemoteApiCodec` / `TRouterRemoteMethodCodec` / `TRouterTypedReply`（core api） | 编解码契约与回包约定（`__trouter_ok` / `__trouter_error`） |
| `TRouter.remoteApi(iface, target, onError)`（core） | 返回 **JDK 动态代理**（动态代理＝运行期自动生成一个"假的接口实现"，你把调用交给它，它负责转发）：调用方写 `api.count("abcd") { n -> ... }`，框架在 worker 线程（后台工作线程，不是主线程）发 AIDL、主线程回调；远端失败/超时/未实现走 `onError`；Object 方法本地处理 |
| `RemoteRouter` | 新增 `Task.Typed`（含 send/recv/fail/超时全链路日志与失败回执（回执＝失败原因会回传给调用方）） |

### 2.2.2 证据

**自动化**：新增 `TRouterTypedApiCrossProcessTest`（6 条，**6/6 通过**，`run-batchC-typedapi.log`）

| 用例 | 断言（检查结果是否符合预期） |
|---|---|
| `typedPrimitiveCallTravelsAcrossProcess` | `count("abcd")` → 8 + `[remote][typed][recv] method=count` 日志 |
| `typedMultiParamEnumAndListTravel` | 多参数 + 枚举 + `List<Int>` → 远端汇总含 `tag=s29` / `level=HIGH` / `sum=6` / 远端 pid |
| `typedPojoResultComesBackDecoded` | 结果 `@RemotePojo` 逐字段一致（id/count/ok/tags/嵌套/枚举） |
| `unregisteredImplementationGoesToOnError` | 目标进程没登记实现 → 走 `onError`（reason 含"未注册"），不静默、不崩 |
| `proxyObjectMethodsAreHandledLocally` | `toString/equals/hashCode` 本地处理，无跨进程调用 |
| `missingClientCodecThrowsImmediately` | 未注册编解码器 → 立即抛错且提示 `registerClient()` |

**回归**：跨进程 7 个测试类（契约/服务/参数/多进程/POJO/类型化/代理）**28/28 通过**（`run-batchC-crossprocess-regression.log`）。

**人工可见**（`run-batchC-typedapi-ui.log`）：点 S29 后状态栏
```
S29 ✓ count=8 ｜ 远端汇总 tag=s29 level=HIGH sum=6 pid=9023 ｜ report=remote-X/count=99/tags=remote|typed/inner=远端内层对象
```
其中 `pid=9023` 正是 `:remote2` 进程 —— 三次类型化调用确实在第三个进程执行。

### 2.2.3 生成器又踩到并修掉的四个问题（KSP 成功 ≠ 生成代码可编译）

1. **回调参数类型判定**：Kotlin 的 `(T) -> Unit` 在 KSP 里是 `kotlin.Function1<T, Unit>`（**两个**泛型实参），只判 `size == 1` 会全部报错；
2. **实参需要强转**：`values[i]` 是 `Any?`，必须按声明类型生成 `(values[0] as String)` 这类转换；
3. **Bundle key 必须加引号**：`putString(a0, …)` 会被当成标识符（本次两处：打包时与分发结果时的 key）；
4. **`object` 内不能声明 `companion object`**：`const val` 直接作为 object 成员即可。

## 四、这一轮小结

- 多进程：host / `:remote` / `:remote2` 三个真实进程，各自独立路由表 + 端点表 + 类型化 API 实现，均有自动化与人工证据；
- 类型化：`@RemotePojo`（对象打包还原，不靠反射）与 `@RemoteApi`（接口 → 动态代理 + 生成的分发代码）都已落地并覆盖错误路径；
- 待办：**统一全量回测 + 总报告 + README 同步**（下一轮执行）。
