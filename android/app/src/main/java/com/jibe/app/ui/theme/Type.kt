package com.jibe.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jibe.app.R

// ── Monospace font for technical values ─────────────────────────────

val RobotoMono =
        FontFamily(
                Font(R.font.roboto_mono_regular, FontWeight.Normal),
                Font(R.font.roboto_mono_medium, FontWeight.Medium),
                Font(R.font.roboto_mono_bold, FontWeight.Bold)
        )

// ── Typography scale ────────────────────────────────────────────────

val JibeTypography =
        Typography(
                headlineLarge =
                        TextStyle(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 28.sp,
                                lineHeight = 36.sp,
                                letterSpacing = (-0.5).sp
                        ),
                headlineSmall =
                        TextStyle(
                                fontWeight = FontWeight.Medium,
                                fontSize = 20.sp,
                                lineHeight = 28.sp,
                                letterSpacing = 0.sp
                        ),
                titleMedium =
                        TextStyle(
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                letterSpacing = 0.15.sp
                        ),
                bodyLarge =
                        TextStyle(
                                fontWeight = FontWeight.Normal,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                letterSpacing = 0.5.sp
                        ),
                bodyMedium =
                        TextStyle(
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                letterSpacing = 0.25.sp
                        ),
                labelSmall =
                        TextStyle(
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                letterSpacing = 0.5.sp
                        ),
                labelMedium =
                        TextStyle(
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                letterSpacing = 0.5.sp
                        ),
                labelLarge =
                        TextStyle(
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                letterSpacing = 0.1.sp
                        )
        )
