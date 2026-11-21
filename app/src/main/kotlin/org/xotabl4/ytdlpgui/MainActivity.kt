package org.xotabl4.ytdlpgui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xotabl4.ytdlpgui.core.AppTheme
import org.xotabl4.ytdlpgui.core.SettingsStore
import org.xotabl4.ytdlpgui.ui.LocalizedContent
import org.xotabl4.ytdlpgui.ui.theme.Theme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      val theme by SettingsStore.theme.collectAsStateWithLifecycle()
      val language by SettingsStore.language.collectAsStateWithLifecycle()
      val dark = theme == AppTheme.Dark

      // Иконки системных панелей должны быть светлыми на тёмной теме и наоборот —
      // иначе в светлой теме время и батарея сливаются с фоном.
      LaunchedEffect(dark) {
        enableEdgeToEdge(
          statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
          navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        )
      }

      LocalizedContent(language) {
        Theme(darkTheme = dark) {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MainNavigation()
          }
        }
      }
    }
  }
}
