package com.jibe.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JibeColorScheme =
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

/** Jibe's Material 3 theme — always dark, no dynamic color. */
@Composable
fun JibeTheme(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = JibeColorScheme, typography = JibeTypography, content = content)
}
