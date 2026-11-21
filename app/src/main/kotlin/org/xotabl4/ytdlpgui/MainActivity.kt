package org.xotabl4.ytdlpgui

import android.content.Intent
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
import org.xotabl4.ytdlpgui.core.SettingsStore
import org.xotabl4.ytdlpgui.core.ShareReceiver
import org.xotabl4.ytdlpgui.ui.LocalizedContent
import org.xotabl4.ytdlpgui.ui.theme.Theme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handleShareIntent(intent)

    setContent {
      val theme by SettingsStore.theme.collectAsStateWithLifecycle()
      val language by SettingsStore.language.collectAsStateWithLifecycle()
      val dark = theme.isDark

      // Иконки системных панелей подстраиваются под текущую тему.
      LaunchedEffect(dark) {
        enableEdgeToEdge(
          statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
          navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        )
      }

      LocalizedContent(language) {
        Theme(palette = theme) {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MainNavigation()
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleShareIntent(intent)
  }

  private fun handleShareIntent(intent: Intent?) {
    if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
      val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
      extractUrl(text)?.let { ShareReceiver.submit(it) }
    }
  }

  private fun extractUrl(text: String): String? =
    Regex("""https?://[^\s"'<>]+""").find(text)?.value?.trim()
}

