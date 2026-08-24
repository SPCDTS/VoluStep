package dev.spcdts.volumemapper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC9FF),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF17496F),
    onPrimaryContainer = Color(0xFFD2E4FF),
    secondary = Color(0xFFBBC7D6),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF303B48),
    onSecondaryContainer = Color(0xFFD7E3F2),
    tertiary = Color(0xFFCAD0D8),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE1E7EC),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE1E7EC),
    surfaceDim = Color(0xFF101418),
    surfaceBright = Color(0xFF363A3F),
    surfaceContainerLowest = Color(0xFF0B0F13),
    surfaceContainerLow = Color(0xFF171B20),
    surfaceContainer = Color(0xFF1B2025),
    surfaceContainerHigh = Color(0xFF252A30),
    surfaceContainerHighest = Color(0xFF30353B),
    surfaceVariant = Color(0xFF20262C),
    onSurfaceVariant = Color(0xFFBEC7D0),
    outline = Color(0xFF89929B),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF17699A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E4FF),
    onPrimaryContainer = Color(0xFF001D35),
    secondary = Color(0xFF51606F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E4F5),
    onSecondaryContainer = Color(0xFF0D1D2A),
    tertiary = Color(0xFF5E6268),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF191C20),
    surfaceDim = Color(0xFFD8DADD),
    surfaceBright = Color(0xFFF8F9FC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F7),
    surfaceContainer = Color(0xFFECEEF2),
    surfaceContainerHigh = Color(0xFFE6E8EC),
    surfaceContainerHighest = Color(0xFFE0E3E7),
    surfaceVariant = Color(0xFFE9EEF3),
    onSurfaceVariant = Color(0xFF41484D),
    outline = Color(0xFF71787E),
    error = Color(0xFFBA1A1A),
)

@Composable
fun VolumeMapperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
