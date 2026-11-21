package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xotabl4.ytdlpgui.R

private const val TAG = "YtDlp"

/** Версия файла `app/src/main/assets/ytdlp` (zipapp из PyPI-пакета yt-dlp). */
private const val BUNDLED_YTDLP_VERSION = "2026.10.26"
private const val KEY_YTDLP_VERSION = "bundled_ytdlp_version_v2"

/** Тестовое видео для проверки связи с YouTube (стабильное, короткое). */
private const val YOUTUBE_TEST_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"

/** Статус разовой операции (обновление/переустановка/проверка) для экрана настроек. */
sealed interface ToolStatus {
  data object Idle : ToolStatus

  /** Операция идёт: [message] — что делаем, [percent] — прогресс, если он измерим. */
  data class Running(val message: String? = null, val percent: Int? = null) : ToolStatus

  data class Done(val text: String) : ToolStatus

  data class Failed(val text: String) : ToolStatus
}

/** Состояние инициализации встроенных yt-dlp (Python) и ffmpeg. */
sealed interface YtDlpState {
  data object Initializing : YtDlpState

  data class Ready(val ytDlpVersion: String, val ffmpegVersion: String?) : YtDlpState

  data class Failed(val message: String) : YtDlpState
}

/** Точка доступа к yt-dlp и ffmpeg. */
object YtDlp {
  /** Версия yt-dlp, вшитая в APK (`assets/ytdlp`) — эталон для цветового индикатора. */
  val bundledYtDlpVersion: String
    get() = BUNDLED_YTDLP_VERSION

  /** Версия ffmpeg, вшитая в APK (`assets/ffmpeg9`) — эталон для цветового индикатора. */
  fun ffmpegReferenceVersion(context: Context): String = FfmpegRuntime.bundledVersion(context)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _state = MutableStateFlow<YtDlpState>(YtDlpState.Initializing)
  val state: StateFlow<YtDlpState> = _state.asStateFlow()

  @Volatile private var initialized = false

  private val _ytdlpStatus = MutableStateFlow<ToolStatus>(ToolStatus.Idle)
  val ytdlpStatus: StateFlow<ToolStatus> = _ytdlpStatus.asStateFlow()

  private val _ffmpegStatus = MutableStateFlow<ToolStatus>(ToolStatus.Idle)
  val ffmpegStatus: StateFlow<ToolStatus> = _ffmpegStatus.asStateFlow()

  private val _youtubeStatus = MutableStateFlow<ToolStatus>(ToolStatus.Idle)
  val youtubeStatus: StateFlow<ToolStatus> = _youtubeStatus.asStateFlow()

  /** «Проверить»: спрашиваем версию у самого yt-dlp (доказывает, что Python+yt-dlp работают). */
  fun checkYtDlp(context: Context) {
    if (_ytdlpStatus.value is ToolStatus.Running) return
    val appContext = context.applicationContext
    scope.launch {
      _ytdlpStatus.value = ToolStatus.Running()
      _ytdlpStatus.value =
        try {
          val version = queryYtDlpVersion()
          publishVersion(ytDlpVersion = version)
          ToolStatus.Done(appContext.getString(R.string.status_ytdlp_ok))
        } catch (e: Exception) {
          Log.e(TAG, "yt-dlp check failed", e)
          ToolStatus.Failed(appContext.getString(R.string.status_failed, shortMessage(e)))
        }
    }
  }

  /** `updateYoutubeDL()` — тянет свежий yt-dlp с GitHub (в некоторых сетях GitHub недоступен). */
  fun updateYtDlp(context: Context) {
    if (_ytdlpStatus.value is ToolStatus.Running) return
    val appContext = context.applicationContext
    scope.launch {
      _ytdlpStatus.value = ToolStatus.Running()
      _ytdlpStatus.value =
        try {
          val status = YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
          val version = queryYtDlpVersion()
          publishVersion(ytDlpVersion = version)
          Log.i(TAG, "updateYoutubeDL: status=$status, version=$version")
          val text =
            if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) R.string.status_no_updates
            else R.string.status_ytdlp_updated
          ToolStatus.Done(appContext.getString(text))
        } catch (e: Exception) {
          Log.e(TAG, "updateYoutubeDL failed", e)
          ToolStatus.Failed(appContext.getString(R.string.status_failed, shortMessage(e)))
        }
    }
  }

  /** Переустановка yt-dlp из APK (`assets/ytdlp`) — работает без интернета. */
  fun reinstallYtDlp(context: Context) {
    if (_ytdlpStatus.value is ToolStatus.Running) return
    val appContext = context.applicationContext
    scope.launch {
      _ytdlpStatus.value = ToolStatus.Running()
      _ytdlpStatus.value =
        try {
          installBundledYtDlp(appContext, force = true)
          val version = queryYtDlpVersion()
          publishVersion(ytDlpVersion = version)
          ToolStatus.Done(appContext.getString(R.string.status_ytdlp_reinstalled))
        } catch (e: Exception) {
          Log.e(TAG, "reinstall yt-dlp failed", e)
          ToolStatus.Failed(appContext.getString(R.string.status_failed, shortMessage(e)))
        }
    }
  }

  /** `ffmpeg -version` — проверка, что бинарь запускается (через лаунчер, как у yt-dlp). */
  fun checkFfmpeg(context: Context) {
    if (_ffmpegStatus.value is ToolStatus.Running) return
    val appContext = context.applicationContext
    scope.launch {
      _ffmpegStatus.value = ToolStatus.Running()
      val version = FfmpegRuntime.version(appContext)
      _ffmpegStatus.value =
        if (version != null) {
          publishVersion(ffmpegVersion = version)
          ToolStatus.Done(appContext.getString(R.string.status_ffmpeg_ok))
        } else {
          ToolStatus.Failed(appContext.getString(R.string.status_ffmpeg_broken))
        }
    }
  }

  /** «Обновить ffmpeg»: берёт сборку из репозитория Termux. */
  fun updateFfmpeg(context: Context) {
    if (_ffmpegStatus.value is ToolStatus.Running) return
    val appContext = context.applicationContext
    scope.launch {
      _ffmpegStatus.value = ToolStatus.Running()
      _ffmpegStatus.value =
        try {
          val installed = FfmpegRuntime.installedVersion(appContext)
          val available = FfmpegUpdater.latestVersion()
          Log.i(TAG, "ffmpeg: установлено=$installed, доступно=$available")
          if (available == null) {
            ToolStatus.Failed(appContext.getString(R.string.status_failed, appContext.getString(R.string.status_repo_unavailable)))
          } else if (installed != null && FfmpegUpdater.compare(installed, available) >= 0) {
            val version = FfmpegRuntime.version(appContext)
            publishVersion(ffmpegVersion = version)
            ToolStatus.Done(appContext.getString(R.string.status_no_updates))
          } else {
            val version = FfmpegUpdater.install(appContext, available) { message, percent ->
              _ffmpegStatus.value = ToolStatus.Running(message, percent)
            }
            publishVersion(ffmpegVersion = version)
            ToolStatus.Done(appContext.getString(R.string.status_ffmpeg_updated, version ?: "?"))
          }
        } catch (e: Exception) {
          Log.e(TAG, "update ffmpeg failed", e)
          ToolStatus.Failed(appContext.getString(R.string.status_failed, shortMessage(e)))
        }
    }
  }

  /** «Переустановить ffmpeg»: заново разворачивает вшитую сборку. */
  fun reinstallFfmpeg(context: Context) {
    if (_ffmpegStatus.value is ToolStatus.Running) return
    val appContext = context.applicationContext
    scope.launch {
      _ffmpegStatus.value = ToolStatus.Running()
      _ffmpegStatus.value =
        try {
          val version = FfmpegRuntime.ensureInstalled(appContext, force = true)
          publishVersion(ffmpegVersion = FfmpegRuntime.version(appContext))
          ToolStatus.Done(appContext.getString(R.string.status_ffmpeg_reinstalled, version))
        } catch (e: Exception) {
          Log.e(TAG, "reinstall ffmpeg failed", e)
          val current = _state.value as? YtDlpState.Ready
          if (current != null) _state.value = current.copy(ffmpegVersion = null)
          ToolStatus.Failed(appContext.getString(R.string.status_failed, shortMessage(e)))
        }
    }
  }

  /** Проверка «есть ли связь с YouTube» — прогон yt-dlp без скачивания файла. */
  fun checkYouTube(context: Context) {
    if (_youtubeStatus.value is ToolStatus.Running) return
    val appContext = context.applicationContext
    scope.launch {
      _youtubeStatus.value = ToolStatus.Running()
      _youtubeStatus.value =
        try {
          val request =
            YoutubeDLRequest(YOUTUBE_TEST_URL).apply {
              addOption("--simulate")
              addOption("--no-warnings")
              addOption("--get-id")
            }
          val response = YoutubeDL.getInstance().execute(request)
          val id = response.out.lines().map { it.trim() }.lastOrNull { it.isNotBlank() }.orEmpty()
          if (response.exitCode == 0 && id.isNotBlank()) {
            ToolStatus.Done(appContext.getString(R.string.status_youtube_ok, id))
          } else {
            val reason = firstErrorLine(response.err).ifBlank { "exit=${response.exitCode}" }
            ToolStatus.Failed(appContext.getString(R.string.status_youtube_failed, reason))
          }
        } catch (e: Exception) {
          Log.e(TAG, "youtube check failed", e)
          ToolStatus.Failed(appContext.getString(R.string.status_youtube_failed, shortMessage(e)))
        }
    }
  }

  private fun publishVersion(ytDlpVersion: String? = null, ffmpegVersion: String? = null) {
    val current = _state.value as? YtDlpState.Ready ?: return
    _state.value =
      current.copy(
        ytDlpVersion = ytDlpVersion ?: current.ytDlpVersion,
        ffmpegVersion = ffmpegVersion ?: current.ffmpegVersion,
      )
  }

  private fun shortMessage(e: Exception): String =
    e.message?.take(200)?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName

  private fun firstErrorLine(text: String): String =
    text.lines().firstOrNull { it.contains("ERROR", ignoreCase = true) }?.removePrefix("ERROR:")?.trim()
      ?: text.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

  fun startInit(context: Context) {
    if (initialized) return
    val appContext = context.applicationContext
    _state.value = YtDlpState.Initializing
    scope.launch {
      _state.value =
        try {
          installBundledYtDlp(appContext)
          YoutubeDL.getInstance().init(appContext)
          FfmpegRuntime.ensureInstalled(appContext)
          initialized = true
          Log.i(TAG, "YoutubeDL.init() + FfmpegRuntime.ensureInstalled() completed")

          val ytDlpVersion = queryYtDlpVersion()
          val ffmpegVersion = FfmpegRuntime.version(appContext)
          Log.i(TAG, "versions: yt-dlp=$ytDlpVersion, ffmpeg=$ffmpegVersion")
          YtDlpState.Ready(ytDlpVersion = ytDlpVersion, ffmpegVersion = ffmpegVersion).also {
            // Настройка «автообновление yt-dlp при запуске» (аналог config.ini → auto_update_ytdlp).
            if (SettingsStore.autoUpdateYtDlp.value) updateYtDlp(appContext)
          }
        } catch (e: Exception) {
          Log.e(TAG, "init failed", e)
          YtDlpState.Failed(e.message ?: e::class.java.simpleName)
        }
    }
  }

  /**
   * `yt-dlp --version` через встроенный Python. Если бинарь не запускается,
   * исключение покажет это лучше любого лога.
   */
  /** Ставит yt-dlp из assets вместо версии из библиотеки. */
  private fun installBundledYtDlp(appContext: Context, force: Boolean = false) {
    // Библиотека запускает файл именно по этому пути.
    val root = File(appContext.noBackupFilesDir, "youtubedl-android/yt-dlp")
    val target = File(root, "yt-dlp")
    val prefs = appContext.getSharedPreferences("bundled_ytdlp", Context.MODE_PRIVATE)
    if (!force && target.exists() && prefs.getString(KEY_YTDLP_VERSION, null) == BUNDLED_YTDLP_VERSION) return

    root.mkdirs()
    appContext.assets.open("ytdlp").use { input ->
      target.outputStream().use { output -> input.copyTo(output) }
    }
    target.setExecutable(true)
    prefs.edit().putString(KEY_YTDLP_VERSION, BUNDLED_YTDLP_VERSION).apply()
    Log.i(TAG, "bundled yt-dlp $BUNDLED_YTDLP_VERSION → ${target.absolutePath} (${target.length()} bytes)")
  }

  private suspend fun queryYtDlpVersion(): String {
    val response = YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList()).addOption("--version"))
    Log.i(TAG, "yt-dlp exit=${response.exitCode} out='${response.out.trim()}' err='${response.err.trim().take(300)}'")
    return response.out.trim().ifEmpty { "unknown" }
  }
}
