package com.trouter.core.api

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import java.io.Serializable

/**
 * 目标页收参助手（取参数的小助手，对齐主流 @Autowired 的“便利性”，但不引入反射）。
 *
 * 目标页创建处用 [of] 读取 Intent extras 或 Fragment arguments，再按类型取值；
 * 取值全部委托 Bundle，行为与手工 get* 一致。跨进程（AIDL Bundle）边界：
 * String/基本类型/Serializable 均可；自定义 Parcelable 仅同 App（两端共享类）可行。
 */
class RouteArgs private constructor(private val src: Bundle?) {

    val isEmpty: Boolean get() = src == null || src.isEmpty

    fun contains(key: String): Boolean = src?.containsKey(key) == true

    fun str(key: String, def: String? = null): String? = src?.getString(key) ?: def

    fun int(key: String, def: Int = 0): Int = src?.getInt(key, def) ?: def

    fun long(key: String, def: Long = 0L): Long = src?.getLong(key, def) ?: def

    fun double(key: String, def: Double = 0.0): Double = src?.getDouble(key, def) ?: def

    fun boolean(key: String, def: Boolean = false): Boolean = src?.getBoolean(key, def) ?: def

    fun strings(key: String): List<String> = src?.getStringArrayList(key)?.toList() ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    fun serializable(key: String, def: Serializable? = null): Serializable? =
        (src?.getSerializable(key) as? Serializable) ?: def

    @Suppress("UNCHECKED_CAST")
    fun <T : Parcelable> parcelable(key: String, def: T? = null): T? =
        src?.getParcelable(key) as? T ?: def

    companion object {
        fun of(intent: Intent?): RouteArgs = of(intent?.extras)

        fun of(bundle: Bundle?): RouteArgs = RouteArgs(bundle)
    }
}
