package com.yourdomain.app.theme // 记得替换成你的包名

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ==========================================
// 1. 定义你绝对掌控的“高定”背景与文字颜色
// ==========================================

// 暗色模式底色（不随壁纸改变）
private val CustomDarkBackground = Color(0xFF121214) // 极深的灰黑色，比纯黑更有质感
private val CustomDarkSurface = Color(0xFF1A1A1E)    // 卡片/容器颜色，略浅于背景
private val CustomDarkText = Color(0xFFE3E2E6)       // 柔和的白色文字，防刺眼

// 亮色模式底色（不随壁纸改变）
private val CustomLightBackground = Color(0xFFF8F9FA) // 冷调灰白，比纯白更高级
private val CustomLightSurface = Color(0xFFFFFFFF)    // 纯白卡片，突出层级
private val CustomLightText = Color(0xFF1C1B1F)       // 深灰文字，非纯黑

// ==========================================
// 2. 预设重点色 (当系统低于 Android 12 时作为降级方案)
// ==========================================
private val FallbackPrimaryDark = Color(0xFFD0BCFF)
private val FallbackOnPrimaryDark = Color(0xFF381E72)
private val FallbackPrimaryContainerDark = Color(0xFF4F378B)
private val FallbackOnPrimaryContainerDark = Color(0xFFEADDFF)

private val FallbackPrimaryLight = Color(0xFF6750A4)
private val FallbackOnPrimaryLight = Color(0xFFFFFFFF)
private val FallbackPrimaryContainerLight = Color(0xFFEADDFF)
private val FallbackOnPrimaryContainerLight = Color(0xFF21005D)


@Composable
fun HybridAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    // 尝试提取 Android 12+ 的系统级动态调色板
    val dynamicColorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        null
    }

    // 核心重构：拼合【系统动态重点色】与【自定义静态背景色】
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            // --- 动态区域：接管自系统（若无则降级） ---
            primary = dynamicColorScheme?.primary ?: FallbackPrimaryDark,
            onPrimary = dynamicColorScheme?.onPrimary ?: FallbackOnPrimaryDark,
            primaryContainer = dynamicColorScheme?.primaryContainer ?: FallbackPrimaryContainerDark,
            onPrimaryContainer = dynamicColorScheme?.onPrimaryContainer ?: FallbackOnPrimaryContainerDark,
            // (Secondary / Tertiary 也可以按需接管，但 Primary 通常够用了)
            
            // --- 静态区域：强制使用高定背景 ---
            background = CustomDarkBackground,
            surface = CustomDarkSurface,
            onBackground = CustomDarkText,
            onSurface = CustomDarkText,
            surfaceVariant = Color(0xFF2B2B30), // 用于次要卡片或分割线
            onSurfaceVariant = Color(0xFFA0A0A5) // 用于次要提示文字或未激活图标
        )
    } else {
        lightColorScheme(
            // --- 动态区域：接管自系统（若无则降级） ---
            primary = dynamicColorScheme?.primary ?: FallbackPrimaryLight,
            onPrimary = dynamicColorScheme?.onPrimary ?: FallbackOnPrimaryLight,
            primaryContainer = dynamicColorScheme?.primaryContainer ?: FallbackPrimaryContainerLight,
            onPrimaryContainer = dynamicColorScheme?.onPrimaryContainer ?: FallbackOnPrimaryContainerLight,
            
            // --- 静态区域：强制使用高定背景 ---
            background = CustomLightBackground,
            surface = CustomLightSurface,
            onBackground = CustomLightText,
            onSurface = CustomLightText,
            surfaceVariant = Color(0xFFE1E2E8),
            onSurfaceVariant = Color(0xFF5A5A60)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        // 这里可以顺便接管 Typography 和 Shapes
        // typography = Typography,
        // shapes = Shapes,
        content = content
    )
}