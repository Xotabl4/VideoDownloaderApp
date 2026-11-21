package org.xotabl4.ytdlpgui.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.xotabl4.ytdlpgui.core.AppTheme

/** Ключевые цвета одной палитры. */
data class PaletteColors(
  val background: Color,
  val surface: Color,
  val surfaceHigh: Color,
  val textPrimary: Color,
  val textSecondary: Color,
  val outline: Color,
  val primary: Color,
  val primaryContainer: Color,
  val pillBackground: Color,
  val pillText: Color,
  val error: Color,
)

/** Цвета палитры: нужны и для темы, и для превью в настройках. */
fun paletteColors(theme: AppTheme): PaletteColors =
  when (theme) {
    AppTheme.Indigo ->
      PaletteColors(
        background = Color(0xFF0E1116),
        surface = Color(0xFF161B22),
        surfaceHigh = Color(0xFF1D2330),
        textPrimary = Color(0xFFF2F3F7),
        textSecondary = Color(0xFF9AA4B2),
        outline = Color(0xFF2A3242),
        primary = Color(0xFF7C6CF6),
        primaryContainer = Color(0xFF5A4FD6),
        pillBackground = Color(0xFFF1EFFA),
        pillText = Color(0xFF1B1B22),
        error = Color(0xFFFF6B6B),
      )
    AppTheme.Oled ->
      PaletteColors(
        background = Color(0xFF000000),
        surface = Color(0xFF121417),
        surfaceHigh = Color(0xFF1A1E22),
        textPrimary = Color(0xFFF2F5F3),
        textSecondary = Color(0xFF8F9AA2),
        outline = Color(0xFF23282E),
        primary = Color(0xFF00E5A0),
        primaryContainer = Color(0xFF00B37E),
        pillBackground = Color(0xFFE9FBF3),
        pillText = Color(0xFF0D2B22),
        error = Color(0xFFFF6B6B),
      )
    AppTheme.Midnight ->
      PaletteColors(
        background = Color(0xFF0A0F1E),
        surface = Color(0xFF141B2E),
        surfaceHigh = Color(0xFF1C2438),
        textPrimary = Color(0xFFEDF0F8),
        textSecondary = Color(0xFF93A0B8),
        outline = Color(0xFF2A3550),
        primary = Color(0xFF4C8DFF),
        primaryContainer = Color(0xFF3667C4),
        pillBackground = Color(0xFFEAF1FF),
        pillText = Color(0xFF14233F),
        error = Color(0xFFFF6B6B),
      )
    AppTheme.Graphite ->
      PaletteColors(
        background = Color(0xFF101113),
        surface = Color(0xFF1B1D20),
        surfaceHigh = Color(0xFF26282C),
        textPrimary = Color(0xFFF2F1EE),
        textSecondary = Color(0xFF9B9C99),
        outline = Color(0xFF303338),
        primary = Color(0xFFFFB74D),
        primaryContainer = Color(0xFFCC8F33),
        pillBackground = Color(0xFFFFF3E0),
        pillText = Color(0xFF2A1E05),
        error = Color(0xFFFF6B6B),
      )
    AppTheme.Cosmos ->
      PaletteColors(
        background = Color(0xFF120A18),
        surface = Color(0xFF1E1526),
        surfaceHigh = Color(0xFF2A1E35),
        textPrimary = Color(0xFFF4ECFA),
        textSecondary = Color(0xFFA998B8),
        outline = Color(0xFF3A2B4A),
        primary = Color(0xFFC084FC),
        primaryContainer = Color(0xFF9660D4),
        pillBackground = Color(0xFFF3E8FF),
        pillText = Color(0xFF241238),
        error = Color(0xFFFF6B6B),
      )
    AppTheme.Paper ->
      PaletteColors(
        background = Color(0xFFF7F7F9),
        surface = Color(0xFFFFFFFF),
        surfaceHigh = Color(0xFFECEDF2),
        textPrimary = Color(0xFF14161C),
        textSecondary = Color(0xFF5B6070),
        outline = Color(0xFFC9CBD8),
        primary = Color(0xFF5B5BD6),
        primaryContainer = Color(0xFFE4E0FF),
        pillBackground = Color(0xFFFFFFFF),
        pillText = Color(0xFF14161C),
        error = Color(0xFFB3261E),
      )
  }

/** Полная Material-схема из палитры. */
fun AppTheme.colorScheme(): ColorScheme {
  val c = paletteColors(this)
  return if (isDark) {
    darkColorScheme(
      primary = c.primary,
      onPrimary = Color.White,
      primaryContainer = c.primaryContainer,
      onPrimaryContainer = Color.White,
      secondary = c.textSecondary,
      tertiary = c.primary,
      background = c.background,
      onBackground = c.textPrimary,
      surface = c.surface,
      onSurface = c.textPrimary,
      surfaceVariant = c.surfaceHigh,
      onSurfaceVariant = c.textSecondary,
      outline = c.outline,
      inverseSurface = c.pillBackground,
      inverseOnSurface = c.pillText,
      error = c.error,
      onError = Color.White,
    )
  } else {
    lightColorScheme(
      primary = c.primary,
      onPrimary = Color.White,
      primaryContainer = c.primaryContainer,
      onPrimaryContainer = Color(0xFF241C4B),
      secondary = c.textSecondary,
      tertiary = c.primary,
      background = c.background,
      onBackground = c.textPrimary,
      surface = c.surface,
      onSurface = c.textPrimary,
      surfaceVariant = c.surfaceHigh,
      onSurfaceVariant = c.textSecondary,
      outline = c.outline,
      inverseSurface = c.pillBackground,
      inverseOnSurface = c.pillText,
      error = c.error,
      onError = Color.White,
    )
  }
}
