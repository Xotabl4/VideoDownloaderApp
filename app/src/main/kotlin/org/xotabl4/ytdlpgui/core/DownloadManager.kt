package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.xotabl4.ytdlpgui.R

private const val TAG = "Download"

/** «Оригинал»: файл сохраняется без конвертации. */
private const val NO_CONVERSION = "Оригинал"

/** Подпапка плейлиста, если название взять неоткуда. */
private const val DEFAULT_PLAYLIST_FOLDER = "Playlist"

/**
 * Сколько загрузок идёт одновременно. Каждая загрузка это отдельный процесс Python
 * и, возможно, склейка ffmpeg, поэтому на телефоне хватает двух; остальные ждут
 * свободного слота и показываются как «В очереди…».
 */
const val MAX_PARALLEL_DOWNLOADS = 2

/** Цели конвертации, которые выполняются через `-x --audio-format`. */
private val AUDIO_TARGETS = setOf("mp3", "m4a", "wav", "flac")

/** Состояние загрузки. */
sealed interface DownloadState {
  data object Idle : DownloadState

  data class Running(
    val percent: Float,
    val etaSeconds: Long?,
    val line: String,
    /** Название ролика — показываем в уведомлении и карточке загрузки. */
    val title: String = "",
  ) : DownloadState

  data class Completed(
    val title: String,
    val filePath: String,
    val uri: String,
    val mimeType: String,
    /** `true` — сохранён плейлист (в подпапку), а не отдельный файл. */
    val isPlaylist: Boolean = false,
  ) : DownloadState

  data class Failed(val message: String) : DownloadState

  data object Canceled : DownloadState
}

/** Загрузка, которая идёт прямо сейчас (для карточек «Загрузок» и уведомлений). */
data class RunningDownload(
  val taskId: String,
  val title: String,
  /** 0–100. */
  val percent: Int,
  val line: String,
)

/** Параметры одной загрузки: своя корутина, свой pid yt-dlp, свой каталог в кэше. */
private class Session(
  val id: String,
  val url: String,
  val formatId: String,
  val mergeFormat: String?,
  val title: String,
  val playlistTitle: String,
  val isPlaylist: Boolean,
  val thumbnail: String,
  val durationSeconds: Long,
) {
  @Volatile var job: Job? = null
  @Volatile var pid: String? = null

  /** Подпапка плейлиста: название от площадки, иначе — название карточки. */
  val folder: String = playlistTitle.ifBlank { title }.ifBlank { DEFAULT_PLAYLIST_FOLDER }
}

/**
 * Порт `downloader.download_video()`, теперь с параллельными загрузками.
 *
 * Десктоп запускал `yt-dlp -f <id> -o <шаблон>` через `Popen` и парсил проценты из stdout;
 * здесь то же делает `YoutubeDL.execute(request, pid, callback)`, а `destroyProcessById(pid)`
 * работает как кнопка «Отмена». Каждая загрузка живёт в своей [Session]: свой процесс, свой
 * временный каталог `cache/downloads/<taskId>`, своё состояние. Одновременно идут не больше
 * [MAX_PARALLEL_DOWNLOADS] задач, остальные ждут слот (см. `Semaphore`).
 */
object DownloadManager {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val slots = Semaphore(MAX_PARALLEL_DOWNLOADS)

  private val sessions = ConcurrentHashMap<String, Session>()

  private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
  /** Состояния всех задач — по ним сервис рисует по уведомлению на каждую загрузку. */
  val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

  private val _running = MutableStateFlow<List<RunningDownload>>(emptyList())
  /** Загрузки, которые идут прямо сейчас (их может быть несколько). */
  val running: StateFlow<List<RunningDownload>> = _running.asStateFlow()

  private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
  /** Состояние «главной» загрузки — последней запущенной (прежний контракт для главного экрана). */
  val state: StateFlow<DownloadState> = _state.asStateFlow()

  private val _activeUrl = MutableStateFlow("")

  /**
   * Ссылка текущей/последней загрузки. Главный экран сравнивает её с содержимым поля:
   * результат показываем, только пока он относится к той же ссылке.
   */
  val activeUrl: StateFlow<String> = _activeUrl.asStateFlow()

  @Volatile private var primaryId: String? = null
  private var lastRequest: Triple<String, String, String?>? = null
  private var lastTitle: String = ""
  private var lastPlaylistTitle: String = ""
  private var lastIsPlaylist: Boolean = false

  /** Идёт хотя бы одна загрузка. */
  val isRunning: Boolean
    get() = _running.value.isNotEmpty()

  /** Сколько загрузок идёт сейчас (без стоящих в очереди). */
  val activeCount: Int
    get() = _running.value.size

  fun start(
    context: Context,
    url: String,
    formatId: String,
    mergeFormat: String? = null,
    thumbnail: String = "",
    durationSeconds: Long = 0L,
    title: String = "",
    /** Название плейлиста: файлы складываем в подпапку с этим именем. */
    playlistTitle: String = "",
    /**
     * Ссылка ведёт на плейлист. Отдельный флаг нужен потому, что не все площадки отдают
     * название подборки (VK отдаёт `title=null`): без него плейлист качался бы как одно
     * видео — в корень «Загрузок» и без подпапки.
     */
    isPlaylist: Boolean = false,
  ): String? {
    val appContext = context.applicationContext
    if (mergeFormat != null && mergeFormat != NO_CONVERSION && !ffmpegReady()) {
      _state.value = DownloadState.Failed(appContext.getString(R.string.error_ffmpeg_missing))
      return null
    }
    lastRequest = Triple(url, formatId, mergeFormat)
    lastTitle = title
    lastPlaylistTitle = playlistTitle
    lastIsPlaylist = isPlaylist || playlistTitle.isNotBlank()

    val taskId = UUID.randomUUID().toString()
    val session =
      Session(
        id = taskId,
        url = url.trim(),
        formatId = formatId,
        mergeFormat = mergeFormat,
        title = title,
        playlistTitle = playlistTitle,
        isPlaylist = lastIsPlaylist,
        thumbnail = thumbnail,
        durationSeconds = durationSeconds,
      )
    sessions[taskId] = session
    primaryId = taskId
    _activeUrl.value = session.url

    // Задача остаётся в «Загрузках» до успеха или удаления.
    DownloadTasks.add(
      appContext,
      DownloadTask(
        id = taskId,
        title = title,
        url = url,
        formatId = formatId,
        conversion = mergeFormat.orEmpty(),
        thumbnail = thumbnail,
        durationSeconds = durationSeconds,
        status = TaskStatus.Running,
        percent = 0,
        message = "",
        updatedAt = System.currentTimeMillis(),
        isPlaylist = session.isPlaylist,
        playlistTitle = playlistTitle,
      ),
    )

    val queued = activeCount >= MAX_PARALLEL_DOWNLOADS
    Log.i(TAG, "start: task=$taskId, format=$formatId, queued=$queued, active=$activeCount")
    publish(
      taskId,
      DownloadState.Running(
        percent = 0f,
        etaSeconds = null,
        line = appContext.getString(if (queued) R.string.download_queued else R.string.download_preparing),
        title = title,
      ),
    )

    session.job = scope.launch { slots.withPermit { run(appContext, session) } }
    return taskId
  }

  /** Публикует состояние задачи и обновляет «главную» загрузку, если это она. */
  private fun publish(taskId: String, state: DownloadState) {
    _states.value = _states.value + (taskId to state)
    if (primaryId == taskId) _state.value = state
    _running.value =
      _states.value.mapNotNull { (id, value) ->
        (value as? DownloadState.Running)?.let { running ->
          RunningDownload(
            taskId = id,
            title = running.title,
            percent = (running.percent * 100).toInt().coerceIn(0, 100),
            line = running.line,
          )
        }
      }
  }

  /** Отмена конкретной задачи (или «главной», если id не передан). */
  fun cancel(taskId: String? = null) {
    val id = taskId ?: primaryId ?: return
    val session = sessions[id] ?: return
    Log.i(TAG, "cancel requested for $id (pid=${session.pid})")
    val pid = session.pid
    if (pid != null) {
      YoutubeDL.getInstance().destroyProcessById(pid)
    } else {
      // Ещё стоит в очереди и не запускала yt-dlp — просто снимаем корутину.
      session.job?.cancel()
    }
  }

  fun retry(context: Context) {
    val (url, formatId, mergeFormat) = lastRequest ?: return
    start(
      context,
      url,
      formatId,
      mergeFormat,
      title = lastTitle,
      playlistTitle = lastPlaylistTitle,
      isPlaylist = lastIsPlaylist,
    )
  }

  /** Сбрасывает результат последней загрузки на главном экране (активные не трогает). */
  fun reset() {
    if (!isRunning) _state.value = DownloadState.Idle
  }

  /** Тело одной загрузки: свой процесс, свой каталог, свои состояния. */
  private suspend fun run(context: Context, session: Session) {
    val taskId = session.id
    val url = session.url
    val formatId = session.formatId
    val mergeFormat = session.mergeFormat
    val title = session.title
    val isPlaylist = session.isPlaylist
    val playlistFolder = session.folder
    val dir = DownloadStorage.tempDir(context, taskId)
    // Перед плейлистом чистим его каталог: fallback публикации берёт из него все готовые файлы.
    if (isPlaylist) DownloadStorage.clearTemp(context, taskId)

    val pid = UUID.randomUUID().toString()
    session.pid = pid
    Log.i(TAG, "run: task=$taskId, format=$formatId, dir=$dir, pid=$pid")
    publish(taskId, DownloadState.Running(0f, null, context.getString(R.string.download_preparing), title))

    // Канал публикации объявлен до `try`: закрыть его нужно и при ошибке, и при отмене,
    // иначе корутина-публикатор останется висеть на незакрытом канале.
    val publishQueue = Channel<String>(Channel.UNLIMITED)
    try {
      // PornHub: ссылку на нужное качество берём из скрытого WebView (см. PhHarvest).
      val isPornHub = PhCookies.isPornHub(url)
      val phQuality = if (formatId.startsWith("ph:")) formatId.removePrefix("ph:").toIntOrNull() else null
      val effectiveUrl =
        if (isPornHub) {
          PhHarvest.urlForQuality(phQuality)
            ?: PhHarvest.resolveAll(context, url).variants.firstOrNull()?.url
            ?: throw IllegalStateException(context.getString(R.string.error_pornhub_stream))
        } else {
          url
        }

      val request =
        YoutubeDLRequest(effectiveUrl).apply {
          // Impersonation (Chrome): тот же отпечаток, что при разборе ссылки.
          if (CurlImpersonate.wantedFor(url) && CurlImpersonate.ensure(context)) {
            addOption("--impersonate", "chrome")
          }
          // Плейлист: у части роликов может не быть выбранного формата — нужен fallback.
          // PornHub: качаем прямую ссылку на выбранное качество целиком.
          addOption(
            "-f",
            when {
              isPornHub -> "best"
              isPlaylist -> "$formatId/bv*+ba/b"
              else -> formatId
            },
          )
          // PornHub: cookies/UA + Referer/Origin, иначе фрагменты HLS отдают 404.
          if (PhCookies.isPornHub(url)) {
            addOption("--cookies", PhCookies.file(context).absolutePath)
            addOption("--user-agent", PhCookies.USER_AGENT)
            addOption("--referer", "https://www.pornhub.com/")
            addOption("--add-header", "Origin: https://www.pornhub.com")
          }
          applyConversion(mergeFormat)
          // PornHub качается прямой ссылкой CDN: имя файла берём из названия видео,
          // иначе yt-dlp подставит мусорное имя из URL.
          val outputName =
            if (isPornHub) {
              val safe = DownloadStorage.sanitize(title.ifBlank { "PornHub" })
              "$safe.%(ext)s"
            } else {
              "%(title)s.%(ext)s"
            }
          addOption("-o", File(dir, outputName).absolutePath)
          addOption("--no-mtime")
          // Прогресс/итог печатаются построчно — так их проще разбирать (и библиотеке, и нам).
          addOption("--newline")
          // Итоговый путь печатается в stdout — проще, чем угадывать имя файла.
          // ВАЖНО: `--print` включает `--quiet`, из-за чего пропадают строки прогресса
          // и колбэк `execute(..., callback)` не срабатывает — возвращаем вывод сами.
          if (isPlaylist) {
            // Для плейлиста печатаем пары «название плейлиста<TAB>путь» — по ним делаем подпапку.
            addOption("--print", "after_move:%(playlist_title|)s\t%(filepath)s")
            // Один битый ролик не должен обрывать весь плейлист.
            addOption("--ignore-errors")
            // Начинаем качать, не дожидаясь разбора всех страниц плейлиста (длинные списки).
            addOption("--lazy-playlist")
            // Если битых подряд слишком много — прекращаем, а не долбим сервис бесконечно.
            addOption("--skip-playlist-after-errors", "5")
          } else {
            addOption("--print", "after_move:filepath")
          }
          addOption("--no-quiet")
          addOption("--progress")
          // Свой (обновляемый) ffmpeg: библиотека больше не поставляет его, а 7.1.1-подсказку
          // из AAR мы убрали — без этой строки yt-dlp не склеит потоки и не сделает mp3.
          if (FfmpegRuntime.isReady(context)) {
            addOption("--ffmpeg-location", FfmpegRuntime.binDir(context).absolutePath)
          }
        }

      // Плейлист: каждый готовый файл публикуем сразу, а не в конце — иначе при 100+ роликах
      // в папке ничего не появляется до последнего ролика, а отмена теряет всё скачанное.
      val subDir = if (isPlaylist) playlistFolder else null
      val queuedPaths = ConcurrentHashMap.newKeySet<String>()
      val publishedPaths = ConcurrentHashMap.newKeySet<String>()
      val publishedCount = AtomicInteger()
      val firstSaved = AtomicReference<SavedFile?>(null)

      /** Публикует готовый файл в «Загрузки», пишет историю и освобождает кэш. */
      suspend fun publishOne(path: String): SavedFile? =
        runCatching {
            val file = File(path)
            val size = file.length()
            val saved = DownloadStorage.publish(context, file, subDir)
            file.delete()
            DownloadHistory.add(
              context,
              DownloadHistory.entryFor(
                // Фактическое имя файла (MediaStore мог добавить «(1)»), а не исходное.
                title = saved.displayPath.substringAfterLast('/').substringBeforeLast('.'),
                saved = saved,
                sizeBytes = size,
                url = url,
                formatId = formatId,
                conversion = mergeFormat,
                thumbnail = session.thumbnail,
                durationSeconds = session.durationSeconds,
                isPlaylist = isPlaylist,
                playlistTitle = playlistFolder,
              ),
            )
            saved
          }
          .onFailure { Log.w(TAG, "publish failed: $path: ${it.message}") }
          .getOrNull()
          ?.also {
            publishedPaths += path
            firstSaved.compareAndSet(null, it)
            publishedCount.incrementAndGet()
          }

      // Публикация идёт в отдельной корутине: колбэк yt-dlp нельзя блокировать.
      val publisher =
        scope.launch(Dispatchers.IO) {
          for (path in publishQueue) publishOne(path)
        }

      // Плейлист с приватными/удалёнными роликами: yt-dlp качает доступные, но завершает
      // процесс с ненулевым кодом, и библиотека бросает исключение. Не теряем уже скачанное.
      val response =
        try {
          YoutubeDL.getInstance().execute(request, pid) { libPercent, libEta, line ->
            // Колбэк библиотеки не всегда отдаёт процент — тогда считаем сами из строки yt-dlp.
            val percent = libPercent.takeIf { it > 0f }?.div(100f) ?: parsePercent(line) ?: 0f
            val eta = libEta.takeIf { it > 0 } ?: parseEtaSeconds(line)
            DownloadTasks.progress(taskId, (percent * 100).toInt())
            publish(
              taskId,
              DownloadState.Running(
                percent = percent.coerceIn(0f, 1f),
                etaSeconds = eta,
                line = line.trim(),
                title = title,
              ),
            )
            if (isPlaylist) {
              // `--print after_move:<плейлист>\t<путь>`: файл финальный — можно публиковать.
              val printed = line.substringAfter('\t', "").trim()
              if (printed.startsWith("/") && File(printed).isFile && queuedPaths.add(printed)) {
                publishQueue.trySend(printed)
              }
            }
          }
        } catch (e: YoutubeDL.CanceledException) {
          throw e
        } catch (e: Exception) {
          // Уже опубликованные файлы не теряем: показываем успех для того, что успели сохранить.
          if (!isPlaylist || (!hasDownloadedFiles(context, taskId) && publishedCount.get() == 0)) throw e
          Log.w(TAG, "playlist finished with errors: ${e.message?.take(300)}")
          null
        }

      // Процесс завершён: допубликовываем готовое, затем смотрим остаток.
      publishQueue.close()
      publisher.join()

      if (response != null && response.err.isNotBlank()) {
        Log.w(TAG, "yt-dlp stderr: ${response.err.take(3000)}")
      }

      // Пути всех готовых файлов: у плейлиста их несколько, у одиночного видео — один.
      // ВАЖНО: у плейлиста строка начинается с таба (`\t<путь>`), поэтому режем её
      // до `trim()`, иначе таб съедается вместе с путём.
      val printedPaths =
        response
          ?.out
          ?.lines()
          .orEmpty()
          .mapNotNull { raw ->
            val path = if (isPlaylist) raw.substringAfter('\t', "").trim() else raw.trim()
            path.takeIf { it.isNotBlank() && File(it).exists() }
          }
      // Страховка: даже если yt-dlp не напечатал пути, публикуем всё, что лежит в кэше задачи.
      val filePaths =
        printedPaths.ifEmpty {
          if (!isPlaylist) emptyList()
          else {
            DownloadStorage.tempDir(context, taskId)
              .listFiles()
              ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
              ?.sortedBy { it.name }
              ?.map { it.absolutePath }
              .orEmpty()
          }
        }
      for (path in filePaths) if (path !in publishedPaths) publishOne(path)

      val saved = publishedCount.get()
      if (saved == 0) {
        val hint =
          response?.err?.lines()?.lastOrNull { it.isNotBlank() }?.trim()?.take(200).orEmpty()
        val message =
          if (hint.isEmpty()) context.getString(R.string.error_no_file_path)
          else context.getString(R.string.error_no_file_path_hint, hint)
        DownloadTasks.fail(context, taskId, message)
        publish(taskId, DownloadState.Failed(message))
        return
      }

      val first = firstSaved.get() ?: return
      Log.i(TAG, "done: task=$taskId, exit=${response?.exitCode ?: -1}, files=$saved, first=${first.displayPath}")
      DownloadTasks.complete(context, taskId)
      publish(
        taskId,
        DownloadState.Completed(
          title = if (isPlaylist) playlistFolder else first.title,
          filePath =
            if (isPlaylist) {
              val folder = DownloadStorage.sanitizeDir(playlistFolder)
              context.getString(
                R.string.download_playlist_saved,
                "${DownloadStorage.destination.value.label}/$folder",
                saved,
              )
            } else {
              first.displayPath
            },
          uri = first.uri,
          mimeType = first.mimeType,
          isPlaylist = isPlaylist,
        ),
      )

    } catch (e: CancellationException) {
      Log.i(TAG, "canceled: $taskId")
      DownloadTasks.cancel(context, taskId)
      publish(taskId, DownloadState.Canceled)
      throw e
    } catch (e: YoutubeDL.CanceledException) {
      Log.i(TAG, "canceled: $taskId")
      DownloadTasks.cancel(context, taskId)
      publish(taskId, DownloadState.Canceled)
    } catch (e: Exception) {
      Log.e(TAG, "failed: $taskId", e)
      val rawError = e.message.orEmpty()
      runCatching {
        val external = context.getExternalFilesDir(null) ?: context.filesDir
        File(external, "last_download_error.txt").writeText(
          "url: $url\ntask: $taskId\n---\n${rawError.take(4000)}",
        )
      }
      val message = DownloadErrors.forUrl(context, url, rawError)
      DownloadTasks.fail(context, taskId, message)
      publish(taskId, DownloadState.Failed(message))
    } finally {
      session.pid = null
      // Канал закрываем всегда: иначе публикатор останется ждать на незакрытом канале.
      runCatching { publishQueue.close() }
      // Недокачанные `.part` после завершения задачи не нужны.
      runCatching { DownloadStorage.clearTemp(context, taskId) }
      sessions.remove(taskId)
    }
  }

  /** Есть ли в кэше задачи скачанные файлы (нужно, когда плейлист завершился с ошибками). */
  private fun hasDownloadedFiles(context: Context, taskId: String): Boolean =
    DownloadStorage.tempDir(context, taskId).listFiles()?.any {
      it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
    } == true

  /** Конвертация: аудио через `-x --audio-format`, видео через `--merge-output-format`. */
  private fun YoutubeDLRequest.applyConversion(mergeFormat: String?) {
    if (mergeFormat.isNullOrBlank() || mergeFormat == NO_CONVERSION) return
    if (mergeFormat in AUDIO_TARGETS) {
      addOption("-x")
      addOption("--audio-format", mergeFormat)
    } else {
      addOption("--merge-output-format", mergeFormat)
    }
    Log.i(TAG, "conversion: $mergeFormat")
  }

  /** Без ffmpeg не работают ни `-x`, ни склейка. */
  private fun ffmpegReady(): Boolean =
    (YtDlp.state.value as? YtDlpState.Ready)?.ffmpegVersion != null

  /** `[download]  12.3% of ...` → 0.123 */
  private fun parsePercent(line: String): Float? =
    PERCENT_REGEX.find(line)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()?.div(100f)

  /** `ETA 00:12` / `ETA 01:02:03` → секунды */
  private fun parseEtaSeconds(line: String): Long? {
    val match = ETA_REGEX.find(line) ?: return null
    val hours = match.groupValues[1].toLongOrNull() ?: 0L
    val minutes = match.groupValues[2].toLongOrNull() ?: 0L
    val seconds = match.groupValues[3].toLongOrNull() ?: 0L
    return hours * 3600 + minutes * 60 + seconds
  }

  private val PERCENT_REGEX = Regex("""(\d{1,3}(?:[.,]\d+)?)%""")
  private val ETA_REGEX = Regex("""ETA\s+(?:(\d{1,2}):)?(\d{1,2}):(\d{2})""")
}
