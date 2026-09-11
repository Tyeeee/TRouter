package com.demo.trouter

import com.trouter.core.internal.RemoteRouterService

/**
 * `:remote2` 进程的跨进程 AIDL 服务（批次 C 多进程演示）。
 *
 * 为什么需要子类：同一个 Service 类不能在 manifest 里声明两次（不同 android:process），
 * 因此 core 把 [RemoteRouterService] 设计为 open，宿主为每个额外进程声明一个空子类即可。
 * 行为完全继承：远端进程自己的 TRouter 解析路由表并打开页面 / 调用本进程注册的端点。
 */
class RemoteRouterServiceSecond : RemoteRouterService()
