package org.xotabl4.ytdlpgui.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = AccentViolet,
    onPrimary = Color.White,
    primaryContainer = AccentVioletDim,
    onPrimaryContainer = Color.White,
    secondary = secondaryDark,
    tertiary = tertiaryDark,
    background = InkBackground,
    onBackground = TextPrimary,
    surface = InkSurface,
    onSurface = TextPrimary,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = InkOutline,
    // Светлая «пилюля» на тёмном фоне.
    inverseSurface = PillBackground,
    inverseOnSurface = PillText,
    error = Color(0xFFFF6B6B),
    onError = Color.White,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = AccentViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E0FF),
    onPrimaryContainer = Color(0xFF241C4B),
    secondary = Color(0xFF5B6172),
    onSecondary = Color.White,
    tertiary = Color(0xFF7A5C9C),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF14161C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFECEDF4),
    onSurfaceVariant = Color(0xFF5B6070),
    outline = Color(0xFFC9CBD8),
    // На светлом фоне пилюля ввода тёмная — контраст сохраняется.
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF14161C),
    error = Color(0xFFB3261E),
    onError = Color.White,
  )

@Composable
fun Theme(
    // Концепт интерфейса тёмный: по умолчанию всегда тёмная схема, светлая — только если явно попросят.
    darkTheme: Boolean = true,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

