package com.example.sandman.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CyberNavyBg = Color(0xFF080F1A)
val CyberSurface = Color(0xFF0D1B2A)
val CyberSurfaceVariant = Color(0xFF142438)
val CyberBorder = Color(0xFF1E3550)
val NeonCyan = Color(0xFF00F5D4)
val NeonGreen = Color(0xFF70E000)
val NeonAmber = Color(0xFFFFB703)
val NeonPink = Color(0xFFFF0054)
val NeonPurple = Color(0xFF9D4EDD)

private val SandmanDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF080F1A),
    primaryContainer = Color(0xFF0B3B3C),
    onPrimaryContainer = NeonCyan,

    secondary = NeonGreen,
    onSecondary = Color(0xFF080F1A),
    secondaryContainer = Color(0xFF1E3A10),
    onSecondaryContainer = NeonGreen,

    tertiary = NeonAmber,
    onTertiary = Color(0xFF080F1A),

    background = CyberNavyBg,
    onBackground = Color(0xFFE2E8F0),

    surface = CyberSurface,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = CyberSurfaceVariant,
    onSurfaceVariant = Color(0xFF94A3B8),

    outline = CyberBorder,
    error = NeonPink
)

@Composable
fun SandmanTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SandmanDarkColorScheme,
        content = content
    )
}
