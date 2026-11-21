package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "Settings"

/** Тема оформления. */
enum class AppTheme(val storageValue: String) {
  Dark("dark"),
  Light("light"),
  ;

  companion object {
    fun from(value: String?): AppTheme = entries.firstOrNull { it.storageValue == value } ?: Dark
  }
}

/** Язык приложения. */
enum class AppLanguage(val tag: String) {
  Ru("ru"),
  En("en"),
  ;

  companion object {
    fun from(value: String?): AppLanguage = entries.firstOrNull { it.tag == value } ?: Ru
  }
}

/** Настройки: тема, язык, автообновление. */
object SettingsStore {
  private const val PREFS = "app_settings"
  private const val KEY_AUTO_UPDATE_YTDLP = "auto_update_ytdlp"
  private const val KEY_THEME = "theme"
  private const val KEY_LANGUAGE = "language"

  private val _autoUpdateYtDlp = MutableStateFlow(false)
  val autoUpdateYtDlp: StateFlow<Boolean> = _autoUpdateYtDlp.asStateFlow()

  private val _theme = MutableStateFlow(AppTheme.Dark)
  val theme: StateFlow<AppTheme> = _theme.asStateFlow()

  private val _language = MutableStateFlow(AppLanguage.Ru)
  val language: StateFlow<AppLanguage> = _language.asStateFlow()

  fun refresh(context: Context) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    _autoUpdateYtDlp.value = prefs.getBoolean(KEY_AUTO_UPDATE_YTDLP, false)
    _theme.value = AppTheme.from(prefs.getString(KEY_THEME, null))
    _language.value = AppLanguage.from(prefs.getString(KEY_LANGUAGE, null))
  }

  fun setAutoUpdateYtDlp(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_AUTO_UPDATE_YTDLP, enabled).apply()
    _autoUpdateYtDlp.value = enabled
  }

  fun setTheme(context: Context, theme: AppTheme) {
    prefs(context).edit().putString(KEY_THEME, theme.storageValue).apply()
    _theme.value = theme
  }

  /** Меняет язык и для строк вне Compose. */
  fun setLanguage(context: Context, language: AppLanguage) {
    prefs(context).edit().putString(KEY_LANGUAGE, language.tag).apply()
    _language.value = language
    applyLanguage(context.applicationContext, language)
  }

  /** Язык для уведомлений и прочих строк вне Compose. */
  fun applyLanguage(context: Context, language: AppLanguage) {
    runCatching {
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION") context.resources.updateConfiguration(config, context.resources.displayMetrics)
      }
      .onFailure { Log.w(TAG, "не удалось применить локаль ${language.tag}: ${it.message}") }
  }

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
