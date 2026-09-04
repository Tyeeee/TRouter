package com.trouter.core.testing

/**
 * 日志收集器：实现 config.logSink，供测试断言日志是否输出（R3 / O1）。
 *
 * V4.0 起 [remote] 日志可能由跨进程客户端 worker 线程写入（主线程守卫日志并存），
 * 因此全部读写做同步化。
 */
class LogRecorder : (String) -> Unit {

    private val lock = Any()
    private val _lines = mutableListOf<String>()

    /** 读取到的全部日志行（按序，快照）。 */
    val lines: List<String> get() = synchronized(lock) { _lines.toList() }

    override fun invoke(line: String) {
        synchronized(lock) { _lines.add(line) }
    }

    fun clear() = synchronized(lock) { _lines.clear() }

    fun count(): Int = synchronized(lock) { _lines.size }
}
