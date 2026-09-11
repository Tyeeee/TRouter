package com.demo.trouter.backtest

/**
 * 回测台自身的路由契约（与 RouterContract 同一条规则：路径只在这里定义一次，任何地方都引用常量）。
 *
 * 为什么单独一个契约对象而不是塞进 core 的 RouterContract：
 * 回测台是**演示宿主自己的页面**，不属于库的公共契约；core 只提供机制，不该知道回测台存在。
 * 编译期闸门（KSP 路径字面量检查）对常量引用一律放行，因此这里照样受保护。
 */
object BacktestContract {

    /** 回测台主页（节点清单 + 一键回测）。 */
    const val PATH_CONSOLE: String = "/backtest"

    /** 参数操作页：页面上真实输入参数、真实点击按钮发起跳转。 */
    const val PATH_FORM: String = "/bt-form"

    /** 中继操作页：它自己再发起一次跳转（模拟"页面里打开另一个页面"）。 */
    const val PATH_RELAY: String = "/bt-relay"

    /** 中继页入参：本次要打开的目标 path。 */
    const val KEY_RELAY_TARGET: String = "bt.relay.target"

    /** 中继页入参：true = 用异步方式打开（navigateAsync）。 */
    const val KEY_RELAY_ASYNC: String = "bt.relay.async"

    /** 中继页回传给发起方的证据文本键。 */
    const val KEY_RELAY_RESULT: String = "bt.relay.result"

    // ---- 回测专用路径：别名/深链/热更这些节点需要"库里原本不存在的路径"。
    //      同样遵守"路径只写一次"的规矩（调用点写死字符串会被 Lint 规则拦下）。

    /** 精确别名：老链接 /legacy/second 指向现役页面。 */
    const val ALIAS_LEGACY_SECOND: String = "/legacy/second"

    /** 正则别名：一批老链接 /legacy/item/<id>。 */
    const val ALIAS_REGEX_LEGACY_ITEM: String = "regex:/legacy/item/.*"

    /** 被正则别名命中的具体链接。 */
    const val ALIAS_LEGACY_ITEM_42: String = "/legacy/item/42"

    /** 别名成环用的两个别名。 */
    const val ALIAS_LOOP_A: String = "/bt-loop-a"
    const val ALIAS_LOOP_B: String = "/bt-loop-b"

    /** 批量热更原子性验证用的"本可以生效"的路径。 */
    const val DYNAMIC_ATOMIC_GOOD: String = "/bt-atomic-good"

    /** 空路径（边界情形：不应崩溃）。 */
    const val PATH_BLANK: String = ""

    /** 指向不存在类的动态路径（验证"注册了但打不开"的运行期降级）。 */
    const val PATH_BROKEN: String = "/bt-broken"
}
