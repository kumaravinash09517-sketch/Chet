package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = MintPrimaryDark,
    onPrimary = OnMintPrimaryDark,
    primaryContainer = DeepEmeraldContainerDark,
    onPrimaryContainer = OnDeepEmeraldContainerDark,
    secondary = JadeSecondaryDark,
    onSecondary = OnJadeSecondaryDark,
    secondaryContainer = DeepJadeContainerDark,
    onSecondaryContainer = OnDeepJadeContainerDark,
    tertiary = AmberAccentTertiary,
    onTertiary = OnAmberAccentTertiary,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = OnAmberContainer,
    background = ObsidianBackground,
    onBackground = MistOnSurface,
    surface = CharcoalSurface,
    onSurface = MistOnSurface,
    surfaceVariant = ElevatedCharcoalSurfaceVariant,
    onSurfaceVariant = SilverSlateOnSurfaceVariant,
    outline = DarkOutline,
    error = CrimsonAlertDark
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = OnEmeraldPrimary,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = OnEmeraldContainer,
    secondary = SignalTealSecondary,
    onSecondary = OnSignalTealSecondary,
    secondaryContainer = SignalTealContainer,
    onSecondaryContainer = OnSignalTealContainer,
    tertiary = AmberAccentTertiary,
    onTertiary = OnAmberAccentTertiary,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = OnAmberContainer,
    background = AlabasterBackground,
    onBackground = InkOnSurface,
    surface = CrispSurface,
    onSurface = InkOnSurface,
    surfaceVariant = SoftSageSurfaceVariant,
    onSurfaceVariant = MutedSlateOnSurfaceVariant,
    outline = SubtleOutline,
    error = CrimsonAlert
)

val HelloSirShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun HelloSirTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HelloSirTypography,
        shapes = HelloSirShapes,
        content = content
    )
}
