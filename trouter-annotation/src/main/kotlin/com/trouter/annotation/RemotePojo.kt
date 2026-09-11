package com.trouter.annotation

/**
 * 跨进程 POJO 标注（多进程与跨进程增强）：标记需要**跨进程传输**的数据类。
 *
 * 处理器会为该类生成 `TRouterPojo_<类名>` 编解码器（零反射）：
 * - `pack(bundle, value)`：把字段逐个写进 Bundle（基础类型 / String / 枚举名 / List / 嵌套 @RemotePojo）；
 * - `unpack(bundle)`：按主构造函数重建对象（含 null 语义）。
 *
 * 为什么要有它：AIDL 只能传 Bundle 基础类型，传业务对象通常要求手写 `Parcelable`；
 * 该注解把"手写序列化"变成**编译期生成**，既不用反射也不需要 implement Parcelable。
 *
 * 约束（处理器强制，违反即编译错误）：
 * - 必须是带**主构造函数**的类（推荐 data class），且构造参数都有同名属性；
 * - 字段类型白名单：String / Int / Long / Float / Double / Boolean（可空亦可）、
 *   枚举、`List<String>` / `List<Int>` / `List<Long>`、以及**同模块**内另一个 @RemotePojo 类；
 * - 其他类型（如 Date、Map、任意对象）不在支持范围 —— 请先转成白名单类型再传，
 *   处理器会直接报错而不是悄悄降级。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RemotePojo
