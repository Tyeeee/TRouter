package com.demo.trouter

import com.trouter.annotation.RemotePojo

/**
 * 多进程与跨进程增强 演示：跨进程传输的业务对象（**不实现 Parcelable**，编解码由 KSP 生成）。
 *
 * 字段刻意覆盖白名单里的各类形态：
 * - 基础类型与 String；
 * - `List<String>`；
 * - 枚举（按 name 传输）；
 * - **可空嵌套 POJO**（null 语义靠 `<key>.__null` 标志位表达）。
 */
@RemotePojo
data class DemoReport(
    val id: String,
    val count: Int,
    val ok: Boolean,
    val tags: List<String>,
    val inner: DemoInner?,
)

/** 嵌套 @RemotePojo（同模块要求）。 */
@RemotePojo
data class DemoInner(
    val name: String,
    val level: DemoLevel,
)

/** 枚举字段按 name 跨进程传输。 */
enum class DemoLevel { LOW, HIGH }
