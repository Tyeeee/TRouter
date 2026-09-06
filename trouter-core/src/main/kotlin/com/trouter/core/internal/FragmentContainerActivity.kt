package com.trouter.core.internal

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/**
 * FRAGMENT 目标承载 Activity（core 内部实现，对外不可见）。
 * 收到 Fragment 类名后实例化并装载进代码生成的容器。
 * 页面类在真正打开时才被加载 —— 满足惰性语义。
 */
class FragmentContainerActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).apply { id = View.generateViewId() }

        // edge-to-edge（targetSdk≥35 强制）系统栏避让：内容不压状态栏/导航栏
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(container)

        // 重建（如旋转）时由 FragmentManager 恢复已装载 Fragment，避免重复 add
        if (savedInstanceState != null) return

        val className = intent.getStringExtra(EXTRA_FRAGMENT_CLASS)
        if (className.isNullOrBlank()) {
            finish()
            return
        }
        val fragment: Fragment = try {
            @Suppress("UNCHECKED_CAST")
            val clazz = Class.forName(className) as Class<out Fragment>
            clazz.getDeclaredConstructor().newInstance().apply {
                // 导航 bundle 透传为 Fragment 参数；剥离容器内部键，避免泄漏到业务参数
                arguments = (intent.extras?.clone() as? Bundle ?: Bundle()).apply {
                    remove(EXTRA_FRAGMENT_CLASS)
                }
            }
        } catch (e: Exception) {
            finish()
            return
        }
        supportFragmentManager
            .beginTransaction()
            .replace(container.id, fragment)
            .commit()
    }

    companion object {
        const val EXTRA_FRAGMENT_CLASS: String = "com.trouter.core.internal.EXTRA_FRAGMENT_CLASS"

        fun intent(context: android.content.Context, fragmentClassName: String): Intent =
            Intent(context, FragmentContainerActivity::class.java)
                .putExtra(EXTRA_FRAGMENT_CLASS, fragmentClassName)
    }
}
