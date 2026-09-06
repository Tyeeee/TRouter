package com.trouter.core.api

/**
 * 参数透传演示契约（demo 用，供 host/feature 各页与测试共用同一组键）：
 * 验证单进程（Activity/Fragment）与跨进程（AIDL Bundle）均能把调用方参数送达目标页。
 */
object DemoParams {
    const val KEY_MSG: String = "demo.param.msg"
    const val KEY_COUNT: String = "demo.param.count"
    const val DEFAULT_MSG: String = "默认参数（调用方未传）"
}
