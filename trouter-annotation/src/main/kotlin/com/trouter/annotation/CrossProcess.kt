package com.trouter.annotation

/**
 * 跨进程路由标注（跨进程版本）：标记「允许经跨进程通道（AIDL）导航」的 @Route 目标。
 *
 * 使用约束（处理器强制）：
 * - 必须与 @Route 同时标注（否则 KSP ERROR）；
 * - 处理器据此生成 CrossProcessPaths 白名单，宿主配置 remoteWhitelist 即引用它；
 * - 未标注的 path 经 navigateRemote 调用会被宿主通道拒绝（Blocked，不触发 onLost）。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class CrossProcess
