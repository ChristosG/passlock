package com.passlock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Stealth OLED palette: true-black + emerald.
val Emerald = Color(0xFF34D399)
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

@Composable
fun PassLockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StealthColors, content = content)
}
