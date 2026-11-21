package org.xotabl4.ytdlpgui.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import org.xotabl4.ytdlpgui.core.AppTheme

/** Оборачивает контент в Material-тему выбранной палитры. */
@Composable
fun Theme(palette: AppTheme, content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = palette.colorScheme(),
    typography = Typography,
    content = content,
  )
}

