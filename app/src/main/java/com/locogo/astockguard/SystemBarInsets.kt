package com.locogo.astockguard

/*
 * 文件职责：统一处理状态栏和导航栏安全区域，避免不同 Activity 重复计算系统窗口边距。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 为 Activity 根视图统一应用系统栏安全区域。
 * Android 15 强制边到边后，若不处理 Insets，顶部标题栏和底部按钮会被系统栏遮挡。
 */
fun AppCompatActivity.applySystemBarInsets(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(root)
}
