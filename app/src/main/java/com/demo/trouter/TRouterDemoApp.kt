package com.demo.trouter

import android.app.Application
import android.content.pm.ApplicationInfo
import com.tlogger.android.AndroidLogSink
import com.tlogger.core.LogLevel
import com.tlogger.core.LoggingConfig
import com.tlogger.core.TLogger
import com.trouter.core.api.TRouter

/**
 * 演示宿主初始化：统一入口装配（多模块 + 跨进程）。
 *
 * 装配分三步，每一步都有单一来源，避免"真实跑的"和"回测跑的"出现偏差：
 * 1. `DemoRouterConfig.create(this)` —— 配置（含日志口、深链白名单、远端进程表）；
 * 2. `TRouter.init` + `TRouter.install(DemoRouteRegistry)` —— 核心初始化与三模块路由聚合；
 * 3. `DemoProcessWiring.apply(this)` —— 各进程自己的端点/类型化接口/拦截器绑定。
 *
 * 注意：Application 在 :remote / :remote2 进程同样执行（各进程独立 init/install 一份 TRouter 路由表），
 * 这正是「远端 TRouter 自己解析」的前提。
 */
class TRouterDemoApp : Application() {

    companion object {
        /** 多进程与跨进程增强：第二个远端目标（:remote2）的逻辑名，与 TRouterConfig.remoteServices 的 key 对应。 */
        const val REMOTE_TARGET_SECOND: String = "remote2"
    }

    override fun onCreate() {
        super.onCreate()
        // 日志：本 App 是**唯一的安装处**（库只"用"日志、不"装"日志）。
        // 装了之后 TRouter 的日志（来源名 TRouter）和全 App 日志都从这里出去，可以一起过滤/上报。
        TLogger.install(
            LoggingConfig.builder()
                .sink(AndroidLogSink())
                .defaultLevel(if (isDebuggable()) LogLevel.DEBUG else LogLevel.WARN)
                .build(),
        )
        // 配置的单一来源：回测台用同一个工厂重新装配，保证"回测跑的配置"与"真实运行的配置"逐字段相同
        TRouter.init(this, DemoRouterConfig.create(this))
        TRouter.install(DemoRouteRegistry)
        DemoProcessWiring.apply(this)
    }

    /** 调试包判断：用 FLAG_DEBUGGABLE，不依赖 BuildConfig（本模块没开 buildConfig）。 */
    private fun isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
