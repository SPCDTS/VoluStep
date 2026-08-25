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
    primary = Color(0xFFB7C8F6),
    onPrimary = Color(0xFF1F2B49),
    primaryContainer = Color(0xFF33466F),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFF9CB0DC),
    onSecondary = Color(0xFF24314D),
    secondaryContainer = Color(0xFF354563),
    onSecondaryContainer = Color(0xFFDCE3FF),
    tertiary = Color(0xFFE5A8C4),
    onTertiary = Color(0xFF49233A),
    tertiaryContainer = Color(0xFF63394F),
    onTertiaryContainer = Color(0xFFFFD8E7),
    background = Color(0xFF131217),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF1A191E),
    onSurface = Color(0xFFE5E1E9),
    surfaceDim = Color(0xFF131217),
    surfaceBright = Color(0xFF3A373E),
    surfaceContainerLowest = Color(0xFF131217),
    surfaceContainerLow = Color(0xFF211F25),
    surfaceContainer = Color(0xFF211F25),
    surfaceContainerHigh = Color(0xFF28262C),
    surfaceContainerHighest = Color(0xFF36333A),
    surfaceVariant = Color(0xFF211F25),
    onSurfaceVariant = Color(0xFFC9C4CD),
    outline = Color(0xFF938E98),
    outlineVariant = Color(0xFF46434B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE5E1E9),
    inverseOnSurface = Color(0xFF313036),
    inversePrimary = Color(0xFF455D92),
    scrim = Color.Black,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF455D92),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3FF),
    onPrimaryContainer = Color(0xFF001A42),
    secondary = Color(0xFF62739D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE3FF),
    onSecondaryContainer = Color(0xFF1C2B4A),
    tertiary = Color(0xFF96516F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E7),
    onTertiaryContainer = Color(0xFF3E0027),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B20),
    surface = Color.White,
    onSurface = Color(0xFF1B1B20),
    surfaceDim = Color(0xFFDDD9E1),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0EDF4),
    surfaceContainer = Color(0xFFF0EDF4),
    surfaceContainerHigh = Color(0xFFEAE7EE),
    surfaceContainerHighest = Color(0xFFE4E1E8),
    surfaceVariant = Color(0xFFF0EDF4),
    onSurfaceVariant = Color(0xFF64616A),
    outline = Color(0xFF77747C),
    outlineVariant = Color(0xFFDDD9E1),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF2F0F7),
    inversePrimary = Color(0xFFB7C8F6),
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
