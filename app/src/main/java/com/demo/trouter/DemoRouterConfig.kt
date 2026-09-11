package com.demo.trouter

import android.content.ComponentName
import android.content.Context
import com.demo.trouter.backtest.RouteLog
import com.demo.trouter.generated.CrossProcessPaths
import com.demo.trouter.generated.TRouterTargetInterceptorNames
import com.trouter.core.api.TRouterConfig
import com.trouter.core.internal.RemoteRouterService

/**
 * 演示宿主的统一装配配置（单一来源）。
 *
 * 为什么把它从 Application 里抽出来：回测台需要在**不改变任何行为**的前提下，
 * 额外挂上两个观测口（日志环形缓冲、onLost 记录器）重新装配一次。
 * 如果配置散落在 Application 里，回测台就只能抄一份——抄的那份迟早和真实装配不一致，
 * 于是"回测通过"就失去意义。抽成单一来源后，回测跑的配置与线上跑的配置**逐字段相同**。
 */
object DemoRouterConfig {

    /** 深链 scheme 白名单（`trouter://app/second?k=v` 这类外部链接）。 */
    val DEEPLINK_SCHEMES: Set<String> = setOf("trouter")

    /**
     * @param extraSink 额外日志口（回测台用于就地断言日志；普通运行传 null）
     * @param onLost    未找到路径时的兜底回调（回测台用于断言"降级回调真的触发了"）
     */
    fun create(
        context: Context,
        extraSink: ((String) -> Unit)? = null,
        onLost: ((String) -> Unit)? = null,
    ): TRouterConfig = TRouterConfig(
        isDebug = true,
        // 日志同时进内存缓冲与 logcat：内存缓冲给回测断言用，logcat 给人排查用
        logSink = { line ->
            RouteLog.append(line)
            android.util.Log.d("TRouter", line)
            extraSink?.invoke(line)
        },
        onLost = onLost,
        interceptors = DemoInterceptors.demoList,
        remoteService = ComponentName(context, RemoteRouterService::class.java),
        remoteWhitelist = CrossProcessPaths.paths,
        targetInterceptorResolver = { className -> TRouterTargetInterceptorNames.namesOf(className) },
        // 异步拦截器超时（S25 回测用：异步耗时 3000ms > 本超时 → Blocked 收口）
        asyncInterceptorTimeoutMs = 1_500L,
        deeplinkSchemes = DEEPLINK_SCHEMES,
        // 第二个跨进程目标（:remote2）。target=null 仍走上面的 remoteService（:remote）
        remoteServices = mapOf(
            TRouterDemoApp.REMOTE_TARGET_SECOND to ComponentName(context, RemoteRouterServiceSecond::class.java),
        ),
    )
}
