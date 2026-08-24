package com.locogo.astockguard.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkFinanceScheme = darkColorScheme(
    primary = Color(0xFF6EA1FF),
    onPrimary = Color(0xFF071B3A),
    primaryContainer = Color(0xFF17345F),
    onPrimaryContainer = Color(0xFFD7E6FF),
    secondary = Color(0xFF8EA5C8),
    secondaryContainer = Color(0xFF283449),
    onSecondaryContainer = Color(0xFFDCE6F7),
    background = Color(0xFF080D16),
    onBackground = Color(0xFFE8ECF4),
    surface = Color(0xFF0E1522),
    onSurface = Color(0xFFE8ECF4),
    surfaceVariant = Color(0xFF192231),
    onSurfaceVariant = Color(0xFFAEB8C8),
    outline = Color(0xFF354155),
    error = Color(0xFFFF6B6B)
)

private val LightFinanceScheme = lightColorScheme(
    primary = Color(0xFF246BCE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E7FF),
    onPrimaryContainer = Color(0xFF0B315F),
    secondary = Color(0xFF526A8C),
    secondaryContainer = Color(0xFFE1E8F2),
    onSecondaryContainer = Color(0xFF243246),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF171B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171B22),
    surfaceVariant = Color(0xFFEEF1F5),
    onSurfaceVariant = Color(0xFF5E6673),
    outline = Color(0xFFD6DBE3),
    error = Color(0xFFB42318)
)

@Composable
fun AStockGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkFinanceScheme else LightFinanceScheme,
        content = content
    )
}
