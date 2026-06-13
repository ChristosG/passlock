package com.passlock.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.passlock.data.AppSettings

// Stealth OLED palette: true-black + emerald.
val Emerald = Color(0xFF34D399)
val EmeraldDark = Color(0xFF0E9F6E)
val OnEmerald = Color(0xFF04130C)
val TrueBlack = Color(0xFF000000)
val SurfaceDark = Color(0xFF0C0F0D)
val SurfaceBorder = Color(0xFF14201A)
val OnDark = Color(0xFFE8EAED)
val MutedText = Color(0xFF8A93A3)

private val StealthColors = darkColorScheme(
    primary = Emerald,
    onPrimary = OnEmerald,
    secondary = Emerald,
    background = TrueBlack,
    onBackground = OnDark,
    surface = SurfaceDark,
    onSurface = OnDark,
    surfaceVariant = SurfaceBorder,
    onSurfaceVariant = MutedText,
)

private val LightColors = lightColorScheme(
    primary = EmeraldDark,
    onPrimary = Color.White,
    secondary = EmeraldDark,
    background = Color(0xFFF6F8F7),
    onBackground = Color(0xFF0C0F0D),
    surface = Color.White,
    onSurface = Color(0xFF0C0F0D),
    surfaceVariant = Color(0xFFE4EAE6),
    onSurfaceVariant = Color(0xFF55605A),
)

@Composable
fun PassLockTheme(themeMode: String = AppSettings.THEME_SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        AppSettings.THEME_DARK -> true
        AppSettings.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) StealthColors else LightColors, content = content)
}
