// 本文件由 TRouter KSP 处理器生成，请勿手改。
package com.demo.trouter.generated

import com.trouter.core.api.GroupLoader
import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouteTargetKind

class GroupLoader_secondary : GroupLoader {
    override val group: String = "secondary"

    override fun routeMetas(): List<RouteMeta> = listOf(
        RouteMeta(
            path = "/about",
            group = "secondary",
            targetClassName = "com.demo.trouter.AboutActivity",
            kind = RouteTargetKind.ACTIVITY,
        )
    )
}
