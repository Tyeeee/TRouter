package com.demo.trouter.backtest

/** 单个回测节点的结果状态。 */
enum class NodeStatus(val label: String) {
    PASS("通过"),
    FAIL("失败"),
    SKIP("跳过"),
}

/** 节点执行失败（断言不成立）——与"节点代码自身抛异常"区分开，便于报告里分别说明。 */
class NodeFailure(message: String) : Exception(message)

/**
 * 一个「测试节点」= 一个功能点 + 一次真实操作 + 一组对**可见效果**的校验。
 *
 * 注意 [expected] 写的是"人能看到什么"，不是"某个方法返回什么"：回测节点要验的是
 * 真实打开的那个页面、那个页面上真实收到的参数、真实回传的结果，
 * 而不是 navigate() 的返回值（返回值只作为旁证）。
 */
data class BacktestNode(
    val id: String,
    val feature: String,
    val title: String,
    val expected: String,
    val body: (BacktestContext) -> Unit,
)

/** 一个节点的执行结果（含证据行，报告里逐条留档）。 */
data class NodeResult(
    val id: String,
    val feature: String,
    val title: String,
    val expected: String,
    val status: NodeStatus,
    val detail: String,
    val evidence: List<String>,
    val costMs: Long,
)

/**
 * 一轮回测的完整报告。
 *
 * 同时提供三种出口：
 * - [toText]：给人看的中文报告；
 * - [logLines]/[summaryLogLine]：给机器解析的稳定单行格式（logcat 抓取 + 自动化断言）；
 * - [failures]：给修复阶段用的失败清单。
 */
data class BacktestReport(
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val results: List<NodeResult>,
    val device: String,
    val hostProcess: String,
    val hostPid: Int,
) {
    val total: Int get() = results.size
    val passCount: Int get() = results.count { it.status == NodeStatus.PASS }
    val failCount: Int get() = results.count { it.status == NodeStatus.FAIL }
    val skipCount: Int get() = results.count { it.status == NodeStatus.SKIP }
    val failures: List<NodeResult> get() = results.filter { it.status == NodeStatus.FAIL }
    val durationMs: Long get() = finishedAtMs - startedAtMs

    /** 机器可解析的单行结论。 */
    fun summaryLogLine(): String =
        "BT|SUMMARY|total=$total|pass=$passCount|fail=$failCount|skip=$skipCount|durationMs=$durationMs"

    /** 机器可解析的逐节点结果行（失败节点附带 detail）。 */
    fun logLines(): List<String> = results.map { r ->
        "BT|NODE|id=${r.id}|status=${r.status.name}|feature=${r.feature}|costMs=${r.costMs}|" +
            "title=${r.title}|detail=${r.detail.replace('\n', ' ')}"
    }

    /** 给人看的中文报告。 */
    fun toText(): String = buildString {
        appendLine("================ TRouter 回测报告 ================")
        appendLine("设备：$device")
        appendLine("宿主进程：$hostProcess (pid=$hostPid)")
        appendLine("结论：共 $total 个节点，通过 $passCount，失败 $failCount，跳过 $skipCount，耗时 ${durationMs}ms")
        appendLine("--------------------------------------------------")
        var lastFeature = ""
        for (r in results) {
            if (r.feature != lastFeature) {
                lastFeature = r.feature
                appendLine("【$lastFeature】")
            }
            appendLine("  ${statusMark(r.status)} ${r.id} ${r.title}（${r.costMs}ms）")
            appendLine("      期望：${r.expected}")
            appendLine("      实测：${r.detail}")
            for (e in r.evidence) appendLine("      证据：$e")
        }
        appendLine("==================================================")
    }

    private fun statusMark(status: NodeStatus): String = when (status) {
        NodeStatus.PASS -> "[通过]"
        NodeStatus.FAIL -> "[失败]"
        NodeStatus.SKIP -> "[跳过]"
    }
}
