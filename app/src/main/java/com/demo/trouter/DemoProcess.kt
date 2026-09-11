package com.demo.trouter

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/**
 * 当前进程名（跨 API 级别安全）。
 *
 * 起因：编译期强制改造2 首次开启 Lint 后，`NewApi` 报出 `Process.myProcessName()` 需要 API 33，
 * 而本工程 minSdk = 24 —— 在 API 24~32 的真机上会直接崩溃。
 * 这里做一次统一收口：33+ 用官方 API，以下用 `/proc/self/cmdline` 兜底，读不到再退回包名。
 */
object DemoProcess {

    fun name(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Process.myProcessName()
        } else {
            readCmdline() ?: context.packageName
        }

    /** 是否运行在指定后缀的独立进程（如 ":remote"）。 */
    fun isInProcess(context: Context, suffix: String): Boolean =
        name(context).endsWith(suffix)

    private fun readCmdline(): String? = try {
        File("/proc/self/cmdline").readText()
            .trimEnd('\u0000')
            .trim()
            .takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        null
    }
}
