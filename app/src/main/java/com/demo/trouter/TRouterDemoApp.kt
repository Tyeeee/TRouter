package com.demo.trouter

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.Process
import com.demo.trouter.generated.CrossProcessPaths
import com.demo.trouter.generated.TRouterPojo_DemoReport
import com.demo.trouter.generated.TRouterRemoteApiRegistry
import com.demo.trouter.generated.TRouterRemoteApi_DemoStatsApi
import com.demo.trouter.generated.TRouterTargetInterceptorNames
import com.trouter.core.api.ChainOutcome
import com.trouter.core.api.InterceptorChain
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterConfig
import com.trouter.core.api.WrappingInterceptor
import com.trouter.core.internal.RemoteRouterService

/**
 * 演示宿主初始化：统一入口装配（V3.0 多模块 + V4.0 跨进程）。
 * - TRouter.init(context, config)：核心初始化（isDebug=true；interceptors 注入 gate+mock 默认关闭；
 *   remoteService=core 的 RemoteRouterService（manifest 置 :remote 进程）；
 *   remoteWhitelist=KSP 生成的 @CrossProcess 白名单）；
 * - TRouter.install(DemoRouteRegistry)：聚合装配 :app + feature-demo + feature-about 三份注册表。
 *
 * 注意：Application 在 :remote 进程同样执行（各进程独立 init/install 一份 TRouter 路由表），
 * 这正是「远端 TRouter 自己解析」的前提；测试环境先 resetForTest 再以 TestConfig 重建，互不冲突。
 */
class TRouterDemoApp : Application() {

    companion object {
        /** 批次 C：第二个远端目标（:remote2）的逻辑名，与 TRouterConfig.remoteServices 的 key 对应。 */
        const val REMOTE_TARGET_SECOND: String = "remote2"
    }

    override fun onCreate() {
        super.onCreate()
        val config = TRouterConfig(
            isDebug = true,
            interceptors = DemoInterceptors.demoList,
            remoteService = ComponentName(this, RemoteRouterService::class.java),
            remoteWhitelist = CrossProcessPaths.paths,
            targetInterceptorResolver = { className -> TRouterTargetInterceptorNames.namesOf(className) },
            // 批次 B：异步拦截器超时（S25 演示用：异步耗时 3000ms > 本超时 → Blocked 收口）
            asyncInterceptorTimeoutMs = 1_500L,
            // 批次 C：第二个跨进程目标（:remote2）。target=null 仍走上面的 remoteService（:remote）
            remoteServices = mapOf(
                REMOTE_TARGET_SECOND to ComponentName(this, RemoteRouterServiceSecond::class.java),
            ),
        )
        TRouter.init(this, config)
        TRouter.install(DemoRouteRegistry)
        // G2-remote：仅在 :remote 进程注册跨进程服务端点（host 不注册 → 测试可证明走的是真实跨进程调用）
        // 进程判定收口在 DemoProcess（minSdk 24 下不能直接用 Process.myProcessName，需 API 33）
        if (DemoProcess.isInProcess(this, ":remote")) {
            TRouter.registerRemoteEndpoint("demoClock") { args ->
                val q = args?.getString("q") ?: "none"
                "clock-v1 q=$q pid=${Process.myPid()}"
            }
        }
        // 批次 C：第三个进程注册自己的端点——端点表按进程独立，
        // 因此 host 用 target="remote2" 能调到 demoClock2，用默认 target 只会拿到"未注册"
        if (DemoProcess.isInProcess(this, ":remote2")) {
            TRouter.registerRemoteEndpoint("demoClock2") { args ->
                val q = args?.getString("q") ?: "none"
                "clock2-v1 q=$q pid=${Process.myPid()}"
            }
            // 批次 C：POJO 跨进程端点——用生成的编解码器把 Bundle 还原成业务对象（不要求 Parcelable）
            TRouter.registerRemoteEndpoint("pojoEcho") { args ->
                val report = TRouterPojo_DemoReport.unpack(args ?: Bundle())
                "pojoEcho ✓ id=${report.id} count=${report.count} ok=${report.ok} " +
                    "tags=${report.tags.joinToString("|")} " +
                    "inner=${report.inner?.name ?: "null"}/${report.inner?.level?.name ?: "-"} pid=${Process.myPid()}"
            }
        }
        // 批次 C：客户端侧注册类型化远程 API 的编解码器（各进程都注册一份，客户端才用得到）
        TRouter.registerRemoteApiClients(TRouterRemoteApiRegistry.all())
        // 批次 C：:remote2 进程登记类型化 API 的实现（host/:remote 不登记 → 用默认 target 调它会拿到"未注册"错误）
        if (DemoProcess.isInProcess(this, ":remote2")) {
            TRouterRemoteApi_DemoStatsApi.register(DemoStatsApiImpl())
        }
        // L3：给 @Interceptor(remoteAudit) 绑一个 no-op 观察者（演示不拦截，仅证明绑定链在跑）
        TRouter.bindTargetInterceptor(
            "remoteAudit",
            object : WrappingInterceptor {
                override fun intercept(chain: InterceptorChain): ChainOutcome = chain.proceed()
            },
        )
    }
}
