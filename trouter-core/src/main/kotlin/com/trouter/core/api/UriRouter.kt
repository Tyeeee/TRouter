package com.trouter.core.api

import android.net.Uri
import android.os.Bundle

/**
 * URI/Scheme 深链解析工具（G1）。
 *
 * 约定：`scheme://host/path?k1=v1&k2=v2` → scheme 白名单由 TRouterConfig.deeplinkSchemes 控制；
 * `path` 段即内部路由 path（例如 trrouter://app/second 对 /second）；query 并入导航参数（query 优先于入参 bundle）。
 */
object UriRouter {

    fun paramsOf(uri: Uri): Bundle {
        val bundle = Bundle()
        val names = uri.queryParameterNames ?: emptySet()
        for (name in names) {
            val value = uri.getQueryParameter(name)
            if (value != null) bundle.putString(name, value)
        }
        return bundle
    }
}
