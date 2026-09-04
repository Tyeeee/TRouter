// 本文件由 TRouter KSP 处理器生成，请勿手改。
package com.demo.trouter.generated

import com.trouter.core.api.GroupLoader
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouteTargetKind

class GroupLoader_default : GroupLoader {
    override val group: String = "default"

    override fun routeMetas(): List<RouteMeta> = listOf(
        RouteMeta(
            path = "/main",
            group = "default",
            targetClassName = "com.demo.trouter.MainActivity",
            kind = RouteTargetKind.ACTIVITY,
        ),
        RouteMeta(
            path = "/fragment-demo",
            group = "default",
            targetClassName = "com.demo.trouter.DemoFragment",
            kind = RouteTargetKind.FRAGMENT,
        ),
        RouteMeta(
            path = "/second",
            group = "default",
            targetClassName = "com.demo.trouter.SecondActivity",
            kind = RouteTargetKind.ACTIVITY,
        )
    )
}
