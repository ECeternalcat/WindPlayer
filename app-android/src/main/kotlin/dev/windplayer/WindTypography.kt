package dev.windplayer

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Sofia Sans (Google Fonts, OFL) — DESIGN.md's recommended open-source stand-in
 * for the proprietary MarkForMC. Static 400/500/700 instances bundled in
 * `res/font/`. Mirrors the desktop `ui-compose` typography.
 */
val SofiaSansFamily = FontFamily(
    Font(R.font.sofia_sans_regular, FontWeight.Normal),
    Font(R.font.sofia_sans_medium, FontWeight.Medium),
    Font(R.font.sofia_sans_bold, FontWeight.Bold)
)

private val WindTypographyRaw = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.72).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.48).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.32).sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 14.sp, letterSpacing = 0.56.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.48.sp)
)

private fun Typography.withFamily(family: FontFamily): Typography {
    val m = TextStyle(fontFamily = family)
    return Typography(
        displayLarge = displayLarge.merge(m), displayMedium = displayMedium.merge(m), displaySmall = displaySmall.merge(m),
        headlineLarge = headlineLarge.merge(m), headlineMedium = headlineMedium.merge(m), headlineSmall = headlineSmall.merge(m),
        titleLarge = titleLarge.merge(m), titleMedium = titleMedium.merge(m), titleSmall = titleSmall.merge(m),
        bodyLarge = bodyLarge.merge(m), bodyMedium = bodyMedium.merge(m), bodySmall = bodySmall.merge(m),
        labelLarge = labelLarge.merge(m), labelMedium = labelMedium.merge(m), labelSmall = labelSmall.merge(m)
    )
}

val WindTypography = WindTypographyRaw.withFamily(SofiaSansFamily)
