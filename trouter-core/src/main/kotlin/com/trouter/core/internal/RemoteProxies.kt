package com.trouter.core.internal

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * AIDL 透明代理封装（D）：用 Java 动态代理把远端 binder（IRouterService）包装成透明代理对象。
 *
 * 意义：RemoteRouter 只面对 [IRouterService] 接口，底层 binder 的绑定/拆包对调用方透明；
 * 代理是未来加"入参清洗/调用统计/降级"的单一扩展点，不改变既有通道语义。
 * 透明保证：navigate 原样委托给被包装对象，Object 方法（toString/hashCode/equals）安全转发。
 */
object RemoteProxies {

    fun delegating(service: IRouterService): IRouterService {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "navigate" -> method.invoke(service, *(args ?: emptyArray()))
                "toString" -> "RemoteRouterProxy@${Integer.toHexString(System.identityHashCode(proxy))}(delegate=$service)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> method.invoke(service, *(args ?: emptyArray()))
            }
        }
        return Proxy.newProxyInstance(
            service.javaClass.classLoader,
            arrayOf(IRouterService::class.java),
            handler,
        ) as IRouterService
    }
}
