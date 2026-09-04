// 本文件由 TRouter KSP 处理器生成，请勿手改。
package com.demo.trouter.generated

import com.trouter.core.api.GroupLoader
import com.trouter.core.api.GroupLoaderRegistry

object TRouterGroupRegistry : GroupLoaderRegistry {
    override fun loaders(): List<GroupLoader> = listOf(
        GroupLoader_default(),
        GroupLoader_secondary()
    )
}
