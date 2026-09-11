package com.demo.trouter

import android.content.Context
import android.os.Bundle
import android.os.Process
import com.demo.trouter.generated.TRouterPojo_DemoReport
import com.demo.trouter.generated.TRouterRemoteApiRegistry
import com.demo.trouter.generated.TRouterRemoteApi_DemoStatsApi
import com.trouter.core.api.ChainOutcome
import com.trouter.core.api.InterceptorChain
import com.trouter.core.api.TRouter
import com.trouter.core.api.WrappingInterceptor

/**
 * 「init/install 之后还要做的那部分装配」的单一来源（各进程按进程名各注册各的）。
 *
 * 为什么单独抽出来：
 * 1. Application.onCreate 每次进程启动都要跑一遍；
 * 2. **回测台每一轮开头也要跑一遍**——因为回测过程中会经历 reset/重新 init（例如先跑了一批
 *    用测试配置的用例），而 reset 会把端点表、类型化接口登记、目标拦截器绑定统统清空。
 *    如果这段装配只写在 Application 里，回测台重新 init 之后就只剩一个"空壳"，
 *    于是跨进程节点会莫名其妙地失败，而且失败原因看起来像库有 bug。
 *
 * 抽成一个函数的收益很直接：**回测跑的装配 = 真实运行的装配**，两者不可能漂移。
 */
object DemoProcessWiring {

    fun apply(context: Context) {
        // 客户端侧注册类型化远程 API 的编解码器（各进程都注册一份，客户端才用得到）
        TRouter.registerRemoteApiClients(TRouterRemoteApiRegistry.all())

        // 仅在 :remote 进程注册跨进程服务端点（host 不注册 → 可证明走的是真实跨进程调用）
        // 进程判定收口在 DemoProcess（minSdk 24 下不能直接用 Process.myProcessName，需 API 33）
        if (DemoProcess.isInProcess(context, ":remote")) {
            TRouter.registerRemoteEndpoint("demoClock") { args ->
                val q = args?.getString("q") ?: "none"
                "clock-v1 q=$q pid=${Process.myPid()}"
            }
            // 回测用：把"本进程最近一次被路由打开的页面收到了什么"回读出来——
            // "跨进程参数真的送达了页面"因此不靠截图，而是由远端进程自己作证
            TRouter.registerRemoteEndpoint("demoLastOpen") { RemoteOpenLog.describe() }
            // 回测收尾用：host 没法直接关掉另一个进程里的页面，让它自己关（等价于用户按返回）
            TRouter.registerRemoteEndpoint("demoCloseTop") { RemoteOpenLog.closeLast() }
        }

        // 第三个进程注册自己的端点——端点表按进程独立，
        // 因此 host 用 target="remote2" 能调到 demoClock2，用默认 target 只会拿到"未注册"
        if (DemoProcess.isInProcess(context, ":remote2")) {
            TRouter.registerRemoteEndpoint("demoClock2") { args ->
                val q = args?.getString("q") ?: "none"
                "clock2-v1 q=$q pid=${Process.myPid()}"
            }
            // POJO 跨进程端点——用生成的编解码器把 Bundle 还原成业务对象（不要求 Parcelable）
            TRouter.registerRemoteEndpoint("pojoEcho") { args ->
                val report = TRouterPojo_DemoReport.unpack(args ?: Bundle())
                "pojoEcho ✓ id=${report.id} count=${report.count} ok=${report.ok} " +
                    "tags=${report.tags.joinToString("|")} " +
                    "inner=${report.inner?.name ?: "null"}/${report.inner?.level?.name ?: "-"} pid=${Process.myPid()}"
            }
            // 回测收尾用：第三个进程的页面同样"自己关自己"
            TRouter.registerRemoteEndpoint("demoCloseTop") { RemoteOpenLog.closeLast() }
            // :remote2 进程登记类型化 API 的实现（host/:remote 不登记 → 默认 target 调它会拿到"未注册"）
            TRouterRemoteApi_DemoStatsApi.register(DemoStatsApiImpl())
        }

        // 给 @Interceptor(remoteAudit) 绑一个 no-op 观察者（演示不拦截，仅证明绑定链在跑）
        TRouter.bindTargetInterceptor(
            "remoteAudit",
            object : WrappingInterceptor {
                override fun intercept(chain: InterceptorChain): ChainOutcome = chain.proceed()
            },
        )
    }
}
