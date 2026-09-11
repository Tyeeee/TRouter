package com.demo.trouter

import android.os.Process

/**
 * [DemoStatsApi] 的远端实现（运行在 :remote2 进程）。
 *
 * 实现里会带上自己的 pid，便于肉眼确认"这段代码跑在第三个进程"。
 * 框架在 binder 线程等待回调（超时 5s），因此这里同步算完直接回调即可；
 * 若实现是异步的（网络/IO），只要在超时内回调同样成立。
 */
class DemoStatsApiImpl : DemoStatsApi {

    override fun count(q: String, onResult: (Int) -> Unit) {
        onResult(q.length * 2)
    }

    override fun report(id: String, onResult: (DemoReport) -> Unit) {
        onResult(
            DemoReport(
                id = "remote-$id",
                count = 99,
                ok = true,
                tags = listOf("remote", "typed"),
                inner = DemoInner("远端内层对象", DemoLevel.HIGH),
            ),
        )
    }

    override fun summarize(tag: String, level: DemoLevel, values: List<Int>, onResult: (String) -> Unit) {
        onResult("远端汇总 tag=$tag level=$level sum=${values.sum()} pid=${Process.myPid()}")
    }
}
