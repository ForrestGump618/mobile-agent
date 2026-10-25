package com.mobileagent.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD3FF),
    onPrimary = Color(0xFF003548),
    primaryContainer = Color(0xFF004D66),
    secondary = Color(0xFFB7CAD6),
    background = Color(0xFF0E1418),
    surface = Color(0xFF0E1418),
    surfaceVariant = Color(0xFF1B2228),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00658A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4E7FF),
    secondary = Color(0xFF4E616D),
    background = Color(0xFFF7FAFC),
    surface = Color(0xFFF7FAFC),
    surfaceVariant = Color(0xFFDCE4EA),
    error = Color(0xFFBA1A1A),
)

@Composable
fun MobileAgentTheme(
    themeMode: String = "system",
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        content = content,
    )
}
