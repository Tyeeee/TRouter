package com.demo.trouter

import android.app.Application
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
        // 配置的单一来源：回测台用同一个工厂重新装配，保证"回测跑的配置"与"真实运行的配置"逐字段相同
        TRouter.init(this, DemoRouterConfig.create(this))
        TRouter.install(DemoRouteRegistry)
        DemoProcessWiring.apply(this)
    }
}
