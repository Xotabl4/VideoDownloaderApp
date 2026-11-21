package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Состояние скачивания обновления. */
sealed interface UpdateDownloadState {
  data object Idle : UpdateDownloadState

  data class Downloading(val percent: Int) : UpdateDownloadState

  data class Ready(val file: File) : UpdateDownloadState

  data class Failed(val message: String) : UpdateDownloadState
}

/** Скачивает APK новой версии и готовит его к установке. */
object UpdateInstaller {
  private const val TAG = "UpdateInstaller"
  private const val DIR = "update"
  private const val FILE = "VideoDownload.apk"
  private const val TIMEOUT_MS = 30_000

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _state = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
  val state: StateFlow<UpdateDownloadState> = _state.asStateFlow()

  private fun target(context: Context): File =
    File(File(context.cacheDir, DIR).apply { mkdirs() }, FILE)

  /** Файл-метка с URL скачанного APK: по ней понимаем, не устарел ли кэш. */
  private fun urlMarker(context: Context): File = File(context.cacheDir, "$DIR/.url")

  private fun cachedUrl(context: Context): String? =
    runCatching { urlMarker(context).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

  private fun rememberUrl(context: Context, url: String) {
    runCatching { urlMarker(context).writeText(url) }
  }

  fun download(context: Context, url: String) {
    if (_state.value is UpdateDownloadState.Downloading) return
    val appContext = context.applicationContext
    val existing = target(appContext)
    // Переиспользуем файл только если он от той же версии; чужой (например, старый релиз
    // из прошлого раза) — качаем заново, иначе установится не та версия.
    if (existing.length() > 0L && cachedUrl(appContext) == url) {
      _state.value = UpdateDownloadState.Ready(existing)
      return
    }
    scope.launch {
      _state.value = UpdateDownloadState.Downloading(0)
      val result =
        runCatching { perform(appContext, url, existing) }
          .getOrElse { error ->
            Log.w(TAG, "download failed: ${error.message}")
            UpdateDownloadState.Failed(error.message ?: "network")
          }
      if (result is UpdateDownloadState.Ready) rememberUrl(appContext, url)
      _state.value = result
    }
  }

  private fun perform(context: Context, url: String, target: File): UpdateDownloadState {
    // Убираем устаревший файл, чтобы rename ниже всегда записывал свежий APK.
    target.delete()
    val connection =
      (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "VideoDownloader")
      }
    try {
      val code = connection.responseCode
      if (code != HttpURLConnection.HTTP_OK) return UpdateDownloadState.Failed("HTTP $code")
      val total = connection.contentLengthLong
      val partial = File(target.parentFile, target.name + ".part")
      connection.inputStream.use { input ->
        partial.outputStream().use { output ->
          val buffer = ByteArray(64 * 1024)
          var read = input.read(buffer)
          var done = 0L
          while (read != -1) {
            output.write(buffer, 0, read)
            done += read
            val percent = if (total > 0) ((done * 100) / total).toInt() else 0
            _state.value = UpdateDownloadState.Downloading(percent)
            read = input.read(buffer)
          }
        }
      }
      if (partial.length() == 0L) return UpdateDownloadState.Failed("empty file")
      if (!partial.renameTo(target)) partial.copyTo(target, overwrite = true)
      partial.delete()
      return UpdateDownloadState.Ready(target)
    } finally {
      connection.disconnect()
    }
  }

  /** После запуска установщика сбрасываем состояние, чтобы кнопка снова работала. */
  fun reset() {
    _state.value = UpdateDownloadState.Idle
  }

  /** Intent системного установщика для скачанного APK. */
  fun installIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    return Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }
}
