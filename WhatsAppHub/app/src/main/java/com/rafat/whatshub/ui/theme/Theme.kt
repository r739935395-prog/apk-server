package com.rafat.whatshub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.rafat.whatshub.database.AccountPalette

val Account1Green = Color(AccountPalette.ACCOUNT_1_GREEN)
val Account2Blue = Color(AccountPalette.ACCOUNT_2_BLUE)
val Account3Purple = Color(AccountPalette.ACCOUNT_3_PURPLE)
val Account4Orange = Color(AccountPalette.ACCOUNT_4_ORANGE)

fun accountColor(accountId: Int): Color = when (accountId) {
    1 -> Account1Green
    2 -> Account2Blue
    3 -> Account3Purple
    4 -> Account4Orange
    else -> Account1Green
}

private val HubDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00A884),
    onPrimary = Color(0xFF041E17),
    primaryContainer = Color(0xFF005C4B),
    onPrimaryContainer = Color(0xFFD9FDD3),
    secondary = Color(0xFF53BDEB),
    onSecondary = Color(0xFF062536),
    background = Color(0xFF0B141A),
    onBackground = Color(0xFFE9EDEF),
    surface = Color(0xFF111B21),
    onSurface = Color(0xFFE9EDEF),
    surfaceVariant = Color(0xFF202C33),
    onSurfaceVariant = Color(0xFF8696A0),
    error = Color(0xFFF15C6D),
    onError = Color(0xFF2B060B)
)

private val HubAmoledColorScheme = HubDarkColorScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF0A0F13),
    surfaceVariant = Color(0xFF161F26)
)

private val HubLightColorScheme = lightColorScheme(
    primary = Color(0xFF008069),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9FDD3),
    onPrimaryContainer = Color(0xFF111B21),
    secondary = Color(0xFF027EB5),
    onSecondary = Color.White,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF111B21),
    surface = Color.White,
    onSurface = Color(0xFF111B21),
    surfaceVariant = Color(0xFFEFF2F5),
    onSurfaceVariant = Color(0xFF54656F)
)

private val HubTypography = Typography(
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun WhatsAppHubTheme(
    themeMode: String = "dark",
    languageCode: String = "en",
    content: @Composable () -> Unit
) {
    val scheme = when (themeMode) {
        "amoled" -> HubAmoledColorScheme
        "light" -> HubLightColorScheme
        else -> HubDarkColorScheme
    }
    val layoutDir = if (languageCode.startsWith("ar")) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        MaterialTheme(
            colorScheme = scheme,
            typography = HubTypography,
            content = content
        )
    }
}
