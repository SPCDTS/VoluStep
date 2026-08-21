package dev.spcdts.volumemapper.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VolumeMapperColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFFA7F3D0),
    onSecondary = Color(0xFF064E3B),
    secondaryContainer = Color(0xFF065F46),
    onSecondaryContainer = Color(0xFFD1FAE5),
    tertiary = Color(0xFFFDE68A),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE7EDF3),
    surface = Color(0xFF111820),
    onSurface = Color(0xFFE7EDF3),
    surfaceVariant = Color(0xFF1A242E),
    onSurfaceVariant = Color(0xFFB8C5D0),
    error = Color(0xFFFFB4AB),
)

@Composable
fun VolumeMapperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VolumeMapperColors,
        content = content,
    )
}
