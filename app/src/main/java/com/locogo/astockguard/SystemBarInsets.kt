package com.locogo.astockguard

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
