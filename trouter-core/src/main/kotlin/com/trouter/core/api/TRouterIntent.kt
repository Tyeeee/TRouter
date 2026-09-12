package com.trouter.core.api

import android.content.Intent

/**
 * 「只解析、不启动」的产物（见 [TRouter.buildIntent]）。
 *
 * 存在的理由：现代写法 `registerForActivityResult(...)` / `ActivityResultLauncher` 要求**调用方自己拿着 Intent** 去
 * `launch`；而 [TRouter.navigateForResult] 走的是老式 `startActivityForResult`，结果只能从 `onActivityResult` 收。
 * 有了本类型，调用方既能用上现代 Activity Result API，又不会丢掉 TRouter 的东西：
 * 路由解析、路径别名、拦截器（登录校验/灰度/兜底）、`NotFound`/`Blocked` 语义、`RouteLaunch` 元数据，一个不少。
 *
 * 语义是密封类、没有 null：**所有失败分支都必须被处理**，与 [TRouterResult] 同一态度。
 */
sealed class TRouterIntent {

    /**
     * 可以启动了：Intent 里已写好 `RouteLaunch` 元数据（path/group/kind/traceId/开销）。
     * 直接交给自己的 launcher 即可：`launcher.launch(result.intent)`。
     */
    class Ready(val meta: RouteMeta, val intent: Intent) : TRouterIntent()

    /** 路径没注册（与 [TRouterResult.NotFound] 同语义，并已按配置触发过 `onLost`）。 */
    class NotFound(val path: String) : TRouterIntent()

    /** 被拦截器拦下 / 目标拦截器未绑定 / 重定向或别名成环 / 需要等待的异步拦截器（改用 buildIntentAsync）。 */
    class Blocked(val path: String, val reason: String) : TRouterIntent()

    /** 没调用 [TRouter.init]。 */
    data object NotInitialized : TRouterIntent()
}
