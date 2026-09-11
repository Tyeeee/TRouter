package com.demo.trouter

import com.demo.trouter.feature.about.generated.TRouterGroupRegistry as AboutFeatureGroupRegistry
import com.demo.trouter.feature.demo.generated.TRouterGroupRegistry as DemoFeatureGroupRegistry
import com.demo.trouter.generated.TRouterGroupRegistry as HostGroupRegistry
import com.trouter.core.api.GroupLoader
import com.trouter.core.api.GroupLoaderRegistry

/**
 * 多模块版本 host 聚合注册表：把「:app 自身路由 + 各 feature 模块路由」**显式**合并为单一注册表，
 * 供 TRouterDemoApp / 测试 provideRegistry 一次聚合装配。
 *
 * - 每个模块独立跑 KSP、各自生成 TRouterGroupRegistry object（包名随模块 namespace）；
 * - 本表是模块清单的**源码可见交汇点**（新增 feature 模块 = 在此加一行 loaders()）；
 * - 页面类仍只存类名字符串（惰性），聚合装配不加载任何页面类。
 */
object DemoRouteRegistry : GroupLoaderRegistry {

    override fun loaders(): List<GroupLoader> =
        HostGroupRegistry.loaders() +
            DemoFeatureGroupRegistry.loaders() +
            AboutFeatureGroupRegistry.loaders()
}
