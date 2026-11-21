package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Результат проверки обновлений. */
sealed interface UpdateState {
  data object Idle : UpdateState

  data object Checking : UpdateState

  data object UpToDate : UpdateState

  data object NoReleases : UpdateState

  data class Available(val version: String, val pageUrl: String) : UpdateState

  data class Failed(val message: String) : UpdateState
}

/**
 * Проверка новой версии по GitHub Releases.
 *
 * Запрос идёт к api.github.com без токена: лимит 60 обращений в час на адрес, одной
 * фоновой проверки в сутки и ручных нажатий это не касается.
 */
object AppUpdate {
  private const val TAG = "AppUpdate"
  private const val PREFS = "app_update"
  private const val KEY_LAST_CHECK = "last_check"
  private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
  private const val TIMEOUT_MS = 10_000

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
  val state: StateFlow<UpdateState> = _state.asStateFlow()

  private val _hasUpdate = MutableStateFlow(false)

  /** Точка на вкладке настроек. */
  val hasUpdate: StateFlow<Boolean> = _hasUpdate.asStateFlow()

  fun currentVersion(context: Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
      }
      .getOrNull()
      .orEmpty()

  /** Фоновая проверка при старте: не чаще раза в 12 часов, ошибки не показываются. */
  fun checkOnStart(context: Context) {
    val appContext = context.applicationContext
    val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val elapsed = System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L)
    if (elapsed < CHECK_INTERVAL_MS) return
    check(appContext)
  }

  /** Проверка по кнопке в настройках или при старте приложения. */
  fun check(context: Context) {
    if (_state.value == UpdateState.Checking) return
    val appContext = context.applicationContext
    scope.launch {
      _state.value = UpdateState.Checking
      val result =
        runCatching { request(appContext) }
          .getOrElse { error ->
            Log.w(TAG, "check failed: ${error.message}")
            UpdateState.Failed(error.message ?: "network")
          }
      _state.value = result
      _hasUpdate.value = result is UpdateState.Available
      if (result !is UpdateState.Failed && result !is UpdateState.NoReleases) {
        appContext
          .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .edit()
          .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
          .apply()
      }
    }
  }

  private fun request(context: Context): UpdateState {
    val connection =
      (URL(Links.LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("User-Agent", "VideoDownloader/${currentVersion(context)}")
        setRequestProperty("Accept", "application/vnd.github+json")
      }
    try {
      val code = connection.responseCode
      if (code == HttpURLConnection.HTTP_NOT_FOUND) return UpdateState.NoReleases
      if (code != HttpURLConnection.HTTP_OK) return UpdateState.Failed("HTTP $code")
      val body = connection.inputStream.bufferedReader().use { it.readText() }
      val json = JSONObject(body)
      val tag = json.optString("tag_name").trim().removePrefix("v")
      if (tag.isBlank()) return UpdateState.Failed("no tag")
      val page = json.optString("html_url").ifBlank { Links.RELEASES }
      return if (isNewer(tag, currentVersion(context))) {
        UpdateState.Available(tag, page)
      } else {
        UpdateState.UpToDate
      }
    } finally {
      connection.disconnect()
    }
  }

  /** Сравнение по числам: 0.4.0 больше 0.3.0, суффиксы вроде -beta не учитываются. */
  internal fun isNewer(remote: String, current: String): Boolean {
    val left = remote.removePrefix("v").split('.', '-', '+').mapNotNull { it.toIntOrNull() }
    val right = current.removePrefix("v").split('.', '-', '+').mapNotNull { it.toIntOrNull() }
    for (index in 0 until maxOf(left.size, right.size)) {
      val a = left.getOrElse(index) { 0 }
      val b = right.getOrElse(index) { 0 }
      if (a != b) return a > b
    }
    return false
  }
}
