package com.jibe.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// ── Color schemes ───────────────────────────────────────────────────

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
                primary = JibePrimaryVariant,
                onPrimary = JibeOnPrimary,
                onSurfaceVariant = JibeOnSurfaceVariant,
                error = JibeError,
                tertiary = JibeAccent
        )

/** Jibe's Material 3 theme */
@Composable
fun JibeTheme(
        darkTheme: Boolean = isSystemInDarkTheme(),
        dynamicColor: Boolean = true,
        content: @Composable () -> Unit
) {
    val colorScheme =
            when {
                dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    val context = LocalContext.current
                    if (darkTheme) dynamicDarkColorScheme(context)
                    else dynamicLightColorScheme(context)
                }
                darkTheme -> JibeDarkColorScheme
                else -> JibeLightColorScheme
            }

    MaterialTheme(colorScheme = colorScheme, typography = JibeTypography, content = content)
}
