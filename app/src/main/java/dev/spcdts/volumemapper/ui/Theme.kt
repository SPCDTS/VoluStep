package dev.spcdts.volumemapper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF1DB954),
    onPrimary = Color(0xFF121212),
    primaryContainer = Color(0xFF173D23),
    onPrimaryContainer = Color(0xFF9DE2B5),
    secondary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFF121212),
    secondaryContainer = Color(0xFF353535),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFB3B3B3),
    onTertiary = Color(0xFF121212),
    tertiaryContainer = Color(0xFF535353),
    onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF181818),
    onSurface = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFF121212),
    surfaceBright = Color(0xFF535353),
    surfaceContainerLowest = Color(0xFF121212),
    surfaceContainerLow = Color(0xFF282828),
    surfaceContainer = Color(0xFF282828),
    surfaceContainerHigh = Color(0xFF353535),
    surfaceContainerHighest = Color(0xFF414141),
    surfaceVariant = Color(0xFF282828),
    onSurfaceVariant = Color(0xFFB3B3B3),
    outline = Color(0xFF737373),
    outlineVariant = Color(0xFF535353),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF121212),
    inversePrimary = Color(0xFF15803D),
    scrim = Color.Black,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF15803D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F5E1),
    onPrimaryContainer = Color(0xFF073817),
    secondary = Color(0xFF535353),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color(0xFF282828),
    tertiary = Color(0xFF535353),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E0E0),
    onTertiaryContainer = Color(0xFF282828),
    background = Color(0xFFF6F6F6),
    onBackground = Color(0xFF121212),
    surface = Color.White,
    onSurface = Color(0xFF121212),
    surfaceDim = Color(0xFFD9D9D9),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F0F0),
    surfaceContainer = Color(0xFFEEEEEE),
    surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF535353),
    outline = Color(0xFF737373),
    outlineVariant = Color(0xFFD0D0D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF282828),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF1DB954),
    scrim = Color.Black,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun VolumeMapperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
