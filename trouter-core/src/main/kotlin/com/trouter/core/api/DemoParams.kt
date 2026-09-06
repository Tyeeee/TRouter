package com.trouter.core.api

/**
 * 参数透传演示契约（demo 用，供 host/feature 各页与测试共用同一组键）：
 * 验证单进程（Activity/Fragment）与跨进程（AIDL Bundle）均能把调用方参数送达目标页。
 */
object DemoParams {
    const val KEY_MSG: String = "demo.param.msg"
    const val KEY_COUNT: String = "demo.param.count"
    const val DEFAULT_MSG: String = "默认参数（调用方未传）"

    /** 跨进程参数回读用的 SharedPreferences（remote 页写入、host 测试读取，跨进程共享同一文件）。 */
    const val PREF_NAME: String = "trouter_cross_param_echo"
    const val PREF_LAST_PATH: String = "last.path"
    const val PREF_LAST_MSG: String = "last.msg"
    const val PREF_LAST_COUNT: String = "last.count"
}
