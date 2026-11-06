package com.chatrabbit.alarm.ui.theme

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

// “聊天兔”主色：紫 + 暖橘
private val RabbitPurple = Color(0xFF6C4DF6)
private val RabbitPurpleLight = Color(0xFFCFC0FF)
private val RabbitOrange = Color(0xFFF2B457)

private val LightColors = lightColorScheme(
    primary = RabbitPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF20005F),
    secondary = Color(0xFF625B71),
    secondaryContainer = Color(0xFFE8DEF8),
    tertiary = RabbitOrange,
    tertiaryContainer = Color(0xFFFFE0A6),
)

private val DarkColors = darkColorScheme(
    primary = RabbitPurpleLight,
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    secondaryContainer = Color(0xFF4A4458),
    tertiary = RabbitOrange,
    tertiaryContainer = Color(0xFF5A4210),
)

/**
 * Material3 主题。Android 12+ 默认跟随系统取色（动态取色），
 * 低版本回落到上面定义的品牌色。
 */
@Composable
fun ChatRabbitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
