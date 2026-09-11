package com.demo.trouter

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.trouter.core.api.DemoParams
import com.trouter.core.api.RouteLaunch
import java.lang.ref.WeakReference

/**
 * 「本进程最近一次被路由打开的页面收到了什么」的进程内记录（回测支撑）。
 *
 * 两个用途，都是为了让跨进程这件事**可验证、可收尾**：
 * 1. 回读：跨进程导航的返回值只能证明"远端 TRouter 认为打开了页面"，证明不了"远端页面真的拿到了参数"。
 *    让远端页面自己把收到的内容记下来、再由 host 经跨进程端点回读，这条链才算验穿（`demoLastOpen`）；
 * 2. 收尾：远端页面在**另一个进程**里，回测台没法直接 finish 它，它留在屏幕上会把回测台压到后台，
 *    后面所有节点就都站在一个错误的前台上跑。让远端页面自己关自己（`demoCloseTop`）最干净。
 *
 * 注意：它只活在**声明它的那个进程**里，这正是它有意义的原因。
 */
object RemoteOpenLog {

    @Volatile
    private var path: String = "(尚未打开任何页面)"

    @Volatile
    private var msg: String = "(无)"

    @Volatile
    private var count: Int = -1

    @Volatile
    private var pid: Int = -1

    @Volatile
    private var lastActivity: WeakReference<Activity>? = null

    fun record(activity: Activity, intent: Intent) {
        path = intent.getStringExtra(RouteLaunch.EXTRA_PATH) ?: "(无路由元数据)"
        msg = intent.getStringExtra(DemoParams.KEY_MSG) ?: "(未收到 msg)"
        count = intent.getIntExtra(DemoParams.KEY_COUNT, -1)
        pid = Process.myPid()
        lastActivity = WeakReference(activity)
    }

    fun describe(): String = "lastOpen path=$path msg=$msg count=$count pid=$pid"

    /** 让本进程最近被路由打开的那个页面自己关掉（回测收尾用）。 */
    fun closeLast(): String {
        val activity = lastActivity?.get() ?: return "no-page"
        val name = activity.javaClass.simpleName
        Handler(Looper.getMainLooper()).post {
            if (!activity.isFinishing) runCatching { activity.finish() }
        }
        return "closed=$name pid=$pid"
    }
}
