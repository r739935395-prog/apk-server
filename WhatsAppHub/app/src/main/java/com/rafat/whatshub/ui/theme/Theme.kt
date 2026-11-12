package com.rafat.whatshub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

// Rafat Hub Brand Palette (Spec Section 11)
val BrandObsidianBg = Color(0xFF0B0B0F)
val BrandCarbonSurface = Color(0xFF15151C)
val BrandGraphiteCard = Color(0xFF1D1D27)
val BrandRefinedTeal = Color(0xFF14B8A6)
val BrandWarmGold = Color(0xFFD4AF37)

// Distinct 4-Account Identity Colors
val Account1Green = Color(0xFF10B981)   // Emerald
val Account2Blue = Color(0xFF3B82F6)    // Royal Blue
val Account3Purple = Color(0xFF8B5CF6)  // Violet
val Account4Orange = Color(0xFFF59E0B)  // Amber

// Semantic Status Colors
val StatusSuccessGreen = Color(0xFF22C55E)
val StatusWarningAmber = Color(0xFFF59E0B)
val StatusErrorRed = Color(0xFFEF4444)

fun accountColor(accountId: Int): Color = when (accountId) {
    1 -> Account1Green
    2 -> Account2Blue
    3 -> Account3Purple
    4 -> Account4Orange
    else -> BrandRefinedTeal
}

// Primary default: Deep Obsidian Luxury Dark (#0B0B0F)
private val ObsidianLuxuryScheme = darkColorScheme(
    primary = BrandRefinedTeal,
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF113537),
    onPrimaryContainer = Color(0xFFF8FAFC),
    secondary = BrandWarmGold,
    onSecondary = Color(0xFF1C1917),
    secondaryContainer = Color(0xFF28241C),
    onSecondaryContainer = Color(0xFFFDE68A),
    background = BrandObsidianBg,
    onBackground = Color(0xFFF8FAFC),
    surface = BrandCarbonSurface,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = BrandGraphiteCard,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    error = StatusErrorRed,
    onError = Color.White
)

// Carbon Slate Dark
private val CarbonSlateDarkScheme = darkColorScheme(
    primary = BrandRefinedTeal,
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFFF8FAFC),
    secondary = BrandWarmGold,
    onSecondary = Color(0xFF1C1917),
    background = Color(0xFF11131A),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF191C26),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF232735),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF3B4256),
    error = StatusErrorRed,
    onError = Color.White
)

private val HubLightScheme = lightColorScheme(
    primary = Color(0xFF0D9488),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF134E4A),
    secondary = Color(0xFFB45309),
    onSecondary = Color.White,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color.White
)

private val HubTypography = Typography(
    headlineSmall = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Normal, lineHeight = 19.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp)
)

fun resolveFontMultiplier(fontScaleKey: String): Float = when (fontScaleKey) {
    "small" -> 0.90f
    "large" -> 1.12f
    "xl" -> 1.24f
    else -> 1.00f
}

@Composable
fun WhatsAppHubTheme(
    themeMode: String = "amoled",
    fontScaleKey: String = "default",
    content: @Composable () -> Unit
) {
    val scheme = when (themeMode) {
        "light" -> HubLightScheme
        "dark" -> CarbonSlateDarkScheme
        else -> ObsidianLuxuryScheme
    }
    val baseDensity = LocalDensity.current
    val customMultiplier = resolveFontMultiplier(fontScaleKey)
    val effectiveDensity = Density(
        density = baseDensity.density,
        fontScale = (baseDensity.fontScale * customMultiplier).coerceIn(0.82f, 1.55f)
    )

    CompositionLocalProvider(LocalDensity provides effectiveDensity) {
        MaterialTheme(
            colorScheme = scheme,
            typography = HubTypography,
            content = content
        )
    }
}
