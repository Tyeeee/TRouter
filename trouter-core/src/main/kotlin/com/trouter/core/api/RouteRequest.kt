package com.trouter.core.api

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 异步导航句柄（批次 B）：由 [TRouter.navigateAsync] 返回，用于取消一次仍在进行中的导航。
 *
 * - [cancel] 幂等：重复调用只有第一次生效；
 * - 取消后：`onResult` 收到 `Blocked(path, "导航已取消…")`（若结果尚未回调），
 *   且异步拦截器**迟到**的 `proceed` 不会打开目标；
 * - 取消对已经结束的导航是**无害的空操作**（不会覆盖已回调的结果）。
 */
class RouteRequest internal constructor(private val onCancel: () -> Unit) {

    private val cancelled = AtomicBoolean(false)

    /** 是否已取消。 */
    val isCancelled: Boolean get() = cancelled.get()

    /** 取消本次导航（幂等）。 */
    fun cancel() {
        if (cancelled.compareAndSet(false, true)) onCancel()
    }
}
