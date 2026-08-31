package com.lsfg.android.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LsfgDarkColorScheme = darkColorScheme(
    primary = LsfgPrimary,
    onPrimary = LsfgOnPrimary,
    primaryContainer = LsfgPrimaryContainer,
    onPrimaryContainer = LsfgOnPrimaryContainer,
    secondary = LsfgSecondary,
    onSecondary = LsfgOnSecondary,
    secondaryContainer = LsfgSecondaryContainer,
    onSecondaryContainer = LsfgOnSecondaryContainer,
    tertiary = LsfgTertiary,
    onTertiary = LsfgOnTertiary,
    tertiaryContainer = LsfgTertiaryContainer,
    onTertiaryContainer = LsfgOnTertiaryContainer,
    error = LsfgError,
    onError = LsfgOnError,
    errorContainer = LsfgErrorContainer,
    onErrorContainer = LsfgOnErrorContainer,
    background = LsfgBackground,
    onBackground = LsfgOnBackground,
    surface = LsfgSurface,
    onSurface = LsfgOnSurface,
    onSurfaceVariant = LsfgOnSurfaceVariant,
    surfaceDim = LsfgSurfaceDim,
    surfaceBright = LsfgSurfaceBright,
    surfaceContainerLowest = LsfgSurfaceContainerLowest,
    surfaceContainerLow = LsfgSurfaceContainerLow,
    surfaceContainer = LsfgSurfaceContainer,
    surfaceContainerHigh = LsfgSurfaceContainerHigh,
    surfaceContainerHighest = LsfgSurfaceContainerHighest,
    outline = LsfgOutline,
    outlineVariant = LsfgOutlineVariant,
)

@Composable
fun LsfgTheme(content: @Composable () -> Unit) {
    val colorScheme = LsfgDarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = LsfgTypography,
        shapes = LsfgShapes,
        content = content,
    )
}
