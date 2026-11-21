package org.xotabl4.ytdlpgui.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import org.xotabl4.ytdlpgui.core.AppLanguage

/**
 * Подменяет локаль для всего Compose-дерева: `stringResource` читает строки из контекста,
 * созданного с выбранным языком, поэтому переключение RU/EN в настройках применяется сразу,
 * без перезапуска приложения и без AppCompat.
 */
@Composable
fun LocalizedContent(language: AppLanguage, content: @Composable () -> Unit) {
  val base = LocalContext.current
  // Эти владельцы обычно вычисляются из LocalContext; после подмены контекста их нужно
  // передать дальше явно, иначе `rememberLauncherForActivityResult` падает
  // с «No ActivityResultRegistryOwner was provided».
  val registryOwner = LocalActivityResultRegistryOwner.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val locale = remember(language) { Locale.forLanguageTag(language.tag) }
  val configuration =
    remember(language) {
      Configuration(base.resources.configuration).apply {
        setLocale(locale)
        setLocales(LocaleList(locale))
      }
    }
  val localized = remember(language) { base.createConfigurationContext(configuration) }
  CompositionLocalProvider(
    LocalContext provides localized,
    LocalConfiguration provides configuration,
    LocalActivityResultRegistryOwner provides requireNotNull(registryOwner) {
      "ActivityResultRegistryOwner отсутствует: LocalizedContent должен вызываться внутри ComponentActivity.setContent"
    },
    LocalLifecycleOwner provides lifecycleOwner,
    content = content,
  )
}
