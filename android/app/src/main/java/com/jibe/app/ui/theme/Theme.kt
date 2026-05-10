package com.jibe.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val JibeDarkColorScheme =
        darkColorScheme(
                primary = JibePrimary,
                onPrimary = JibeOnPrimary,
                surface = JibeSurfaceDark,
                surfaceContainer = JibeSurfaceContainer,
                surfaceContainerHigh = JibeSurfaceContainerHigh,
                surfaceContainerHighest = JibeSurfaceContainerHighest,
                onSurface = JibeOnSurface,
                onSurfaceVariant = JibeOnSurfaceVariant,
                error = JibeError,
                tertiary = JibeAccent
        )

private val JibeLightColorScheme =
        lightColorScheme(
                primary = JibeLightPrimary,
                onPrimary = JibeLightOnPrimary,
                surface = JibeLightSurface,
                surfaceContainer = JibeLightSurfaceContainer,
                surfaceContainerHigh = JibeLightSurfaceContainerHigh,
                surfaceContainerHighest = JibeLightSurfaceContainerHighest,
                onSurface = JibeLightOnSurface,
                onSurfaceVariant = JibeLightOnSurfaceVariant,
                error = JibeError,
                tertiary = JibeLightPrimary
        )

/**
 * Jibe Material 3 theme — dark default for the main shell; [isDark] toggles a restrained light
 * palette from Settings.
 */
@Composable
fun JibeTheme(isDark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
            colorScheme = if (isDark) JibeDarkColorScheme else JibeLightColorScheme,
            typography = JibeTypography,
            content = content
    )
}
