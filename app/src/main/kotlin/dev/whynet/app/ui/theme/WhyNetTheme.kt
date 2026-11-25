package dev.whynet.app.ui.theme

import android.app.Activity
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat

enum class AppThemeMode(val title: String, val titleEn: String) {
    DARK("Тёмная", "Dark"),
    LIGHT("Светлая", "Light"),
    ;

    companion object {
        fun fromKey(key: String?): AppThemeMode =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: DARK
    }
}

enum class AppLanguage(val title: String) {
    RU("Русский"),
    EN("English"),
    ;

    companion object {
        fun fromKey(key: String?): AppLanguage =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: RU
    }
}

val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.RU }

@Composable
fun tr(ru: String, en: String): String =
    if (LocalAppLanguage.current == AppLanguage.EN) en else ru

@Immutable
data class WhyNetPalette(
    val okFg: Color,
    val okBg: Color,
    val warnFg: Color,
    val warnBg: Color,
    val failFg: Color,
    val failBg: Color,
    val infoFg: Color,
    val infoBg: Color,
    val border: Color,
)

private val LightWhyNetPalette = WhyNetPalette(
    okFg = Color(0xFF16A34A),
    okBg = Color(0xFFDCFCE7),
    warnFg = Color(0xFFD97706),
    warnBg = Color(0xFFFEF3C7),
    failFg = Color(0xFFDC2626),
    failBg = Color(0xFFFEE2E2),
    infoFg = Color(0xFF2563EB),
    infoBg = Color(0xFFDBEAFE),
    border = Color(0xFFD8E0EC),
)

private val DarkWhyNetPalette = WhyNetPalette(
    okFg = Color(0xFF22C55E),
    okBg = Color(0xFF102A1C),
    warnFg = Color(0xFFF59E0B),
    warnBg = Color(0xFF2E220B),
    failFg = Color(0xFFEF4444),
    failBg = Color(0xFF331417),
    infoFg = Color(0xFF4F8CFF),
    infoBg = Color(0xFF132440),
    border = Color(0xFF273142),
)

private val AmoledWhyNetPalette = DarkWhyNetPalette.copy(
    border = Color(0xFF1E2633),
)

val LocalWhyNetPalette = staticCompositionLocalOf { DarkWhyNetPalette }

object WhyNetColors {
    val palette: WhyNetPalette
        @Composable get() = LocalWhyNetPalette.current

    val OkGreen: Color @Composable get() = palette.okFg
    val OkContainer: Color @Composable get() = palette.okBg
    val WarnAmber: Color @Composable get() = palette.warnFg
    val WarnContainer: Color @Composable get() = palette.warnBg
    val FailRed: Color @Composable get() = palette.failFg
    val FailContainer: Color @Composable get() = palette.failBg
    val InfoBlue: Color @Composable get() = palette.infoFg
    val InfoContainer: Color @Composable get() = palette.infoBg
    val Border: Color @Composable get() = palette.border
    val StopButtonBg: Color = Color(0xFFD32F2F)
}

private val PingDarkScheme = darkColorScheme(
    primary = Color(0xFF4F8CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1D2D4D),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFF06B6D4),
    onSecondary = Color(0xFF001F24),
    secondaryContainer = Color(0xFF1D2533),
    onSecondaryContainer = Color(0xFFE6EDF7),
    background = Color(0xFF0F131A),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF0F131A),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF1D2533),
    onSurfaceVariant = Color(0xFF8B99B0),
    surfaceContainerLowest = Color(0xFF161C26),
    surfaceContainerLow = Color(0xFF161C26),
    surfaceContainer = Color(0xFF1A2230),
    surfaceContainerHigh = Color(0xFF1D2533),
    outline = Color(0xFF273142),
    outlineVariant = Color(0xFF273142),
)

private val PingAmoledScheme = PingDarkScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF0B0E13),
    surfaceContainerLow = Color(0xFF0B0E13),
    surfaceContainer = Color(0xFF10151E),
    surfaceContainerHigh = Color(0xFF121720),
    surfaceVariant = Color(0xFF121720),
    outline = Color(0xFF1E2633),
    outlineVariant = Color(0xFF1E2633),
)

private val PingLightScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEF2F8),
    onSecondaryContainer = Color(0xFF111827),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF5F7FB),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEEF2F8),
    onSurfaceVariant = Color(0xFF5B677A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFEEF2F8),
    outline = Color(0xFFD8E0EC),
    outlineVariant = Color(0xFFD8E0EC),
)

private val CompactPlatformStyle = PlatformTextStyle(includeFontPadding = false)
private val CompactLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

private val BaseTypography = Typography()

private val WhyNetTypography = Typography(
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontSize = 20.sp,
        lineHeight = 24.sp,
        platformStyle = CompactPlatformStyle,
        lineHeightStyle = CompactLineHeightStyle,
    ),
    titleLarge = BaseTypography.titleLarge.copy(
        fontSize = 18.sp,
        lineHeight = 22.sp,
        platformStyle = CompactPlatformStyle,
        lineHeightStyle = CompactLineHeightStyle,
    ),
    titleMedium = BaseTypography.titleMedium.copy(
        fontSize = 15.sp,
        lineHeight = 19.sp,
        platformStyle = CompactPlatformStyle,
        lineHeightStyle = CompactLineHeightStyle,
    ),
    titleSmall = BaseTypography.titleSmall.copy(
        fontSize = 13.sp,
        lineHeight = 17.sp,
        platformStyle = CompactPlatformStyle,
        lineHeightStyle = CompactLineHeightStyle,
    ),
    bodyLarge = BaseTypography.bodyLarge.copy(
        fontSize = 13.sp,
        lineHeight = TextUnit.Unspecified,
        platformStyle = CompactPlatformStyle,
        lineHeightStyle = CompactLineHeightStyle,
    ),
    bodyMedium = BaseTypography.bodyMedium.copy(
        fontSize = 12.5.sp,
        lineHeight = 16.5.sp,
        platformStyle = CompactPlatformStyle,
        lineHeightStyle = CompactLineHeightStyle,
    ),
    bodySmall = BaseTypography.bodySmall.copy(
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        platformStyle = CompactPlatformStyle,
        lineHeightStyle = CompactLineHeightStyle,
    ),
    labelLarge = BaseTypography.labelLarge.copy(
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        platformStyle = CompactPlatformStyle,
        lineHeightStyle = CompactLineHeightStyle,
    ),
    labelMedium = BaseTypography.labelMedium.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        platformStyle = CompactPlatformStyle,
        lineHeightStyle = CompactLineHeightStyle,
    ),
    labelSmall = BaseTypography.labelSmall.copy(
        fontSize = 10.sp,
        lineHeight = 13.sp,
        platformStyle = CompactPlatformStyle,
        lineHeightStyle = CompactLineHeightStyle,
    ),
)

@Composable
fun WhyNetTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    language: AppLanguage = AppLanguage.RU,
    content: @Composable () -> Unit,
) {
    val (colorScheme, palette) = when (themeMode) {
        AppThemeMode.DARK -> PingDarkScheme to DarkWhyNetPalette
        AppThemeMode.LIGHT -> PingLightScheme to LightWhyNetPalette
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowInsetsControllerCompat(window, view)
            val isLight = themeMode == AppThemeMode.LIGHT
            controller.isAppearanceLightStatusBars = isLight
            controller.isAppearanceLightNavigationBars = isLight
        }
    }

    CompositionLocalProvider(
        LocalWhyNetPalette provides palette,
        LocalAppLanguage provides language,
        LocalMinimumInteractiveComponentSize provides 32.dp,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WhyNetTypography,
            content = content,
        )
    }
}
