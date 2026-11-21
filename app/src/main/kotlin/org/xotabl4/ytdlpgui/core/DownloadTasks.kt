package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xotabl4.ytdlpgui.R

private const val TAG = "DownloadTasks"
private const val PREFS = "download_tasks"
private const val KEY_ENTRIES = "entries"

/** Статус незавершённой загрузки. */
enum class TaskStatus(val storage: String) {
  Running("running"),
  Canceled("canceled"),
  Failed("failed");

  companion object {
    fun from(value: String?): TaskStatus = entries.firstOrNull { it.storage == value } ?: Running
  }
}

/** Отменённая, упавшая или идущая загрузка: остаётся в «Загрузках» до удаления. */
data class DownloadTask(
  val id: String,
  val title: String,
  val url: String,
  val formatId: String,
  val conversion: String,
  val thumbnail: String,
  val durationSeconds: Long,
  val status: TaskStatus,
  val percent: Int,
  val message: String,
  val updatedAt: Long,
  /** `true` — это плейлист: «Повторить» должно снова качать его в подпапку. */
  val isPlaylist: Boolean = false,
  /** Название подпапки плейлиста (для повтора). */
  val playlistTitle: String = "",
)

/** Незавершённые загрузки: идущие, отменённые и упавшие. */
object DownloadTasks {
  const val LIMIT = 30

  private val _entries = MutableStateFlow<List<DownloadTask>>(emptyList())
  val entries: StateFlow<List<DownloadTask>> = _entries.asStateFlow()

  /** Несколько загрузок идут параллельно и пишут прогресс из разных потоков. */
  private val lock = Any()

  @Volatile private var loaded = false

  fun refresh(context: Context) {
    if (loaded) return
    val raw = prefs(context).getString(KEY_ENTRIES, null).orEmpty()
    // Запись в статусе Running после перезапуска означает, что процесс загрузки был убит системой.
    _entries.value =
      decode(raw).map {
        if (it.status == TaskStatus.Running) {
          it.copy(status = TaskStatus.Failed, message = context.getString(R.string.task_interrupted))
        } else {
          it
        }
      }
    loaded = true
  }

  /** Регистрирует новую загрузку (или обновляет существующую с тем же id). */
  fun add(context: Context, task: DownloadTask) {
    synchronized(lock) {
      ensureLoaded(context)
      _entries.value = (listOf(task) + _entries.value.filterNot { it.id == task.id }).take(LIMIT)
      persist(context)
    }
  }

  /** Прогресс обновляем только в памяти: колбэки yt-dlp приходят несколько раз в секунду. */
  fun progress(id: String, percent: Int) {
    synchronized(lock) {
      val current = _entries.value.firstOrNull { it.id == id } ?: return
      val value = percent.coerceIn(0, 100)
      if (current.percent == value && current.status == TaskStatus.Running) return
      _entries.value =
        _entries.value.map {
          if (it.id == id) it.copy(percent = value, status = TaskStatus.Running) else it
        }
    }
  }

  fun cancel(context: Context, id: String) {
    synchronized(lock) {
      ensureLoaded(context)
      _entries.value =
        _entries.value.map { if (it.id == id) it.copy(status = TaskStatus.Canceled, updatedAt = now()) else it }
      persist(context)
      Log.i(TAG, "canceled task $id")
    }
  }

  fun fail(context: Context, id: String, message: String) {
    synchronized(lock) {
      ensureLoaded(context)
      _entries.value =
        _entries.value.map {
          if (it.id == id) {
            it.copy(status = TaskStatus.Failed, message = message.take(300), updatedAt = now())
          } else {
            it
          }
        }
      persist(context)
      Log.i(TAG, "failed task $id: ${message.take(120)}")
    }
  }

  fun encode(entries: List<DownloadTask>): String =
    entries.joinToString("\n") { task ->
      listOf(
          task.id,
          task.updatedAt.toString(),
          task.status.storage,
          task.percent.toString(),
          task.title,
          task.url,
          task.formatId,
          task.conversion,
          task.thumbnail,
          task.durationSeconds.toString(),
          task.message,
          if (task.isPlaylist) "1" else "0",
          task.playlistTitle,
        )
        .joinToString("\t") { escape(it) }
    }

  fun decode(raw: String): List<DownloadTask> =
    raw.lineSequence()
      .filter { it.isNotBlank() }
      .mapNotNull { line ->
        val parts = line.split('\t').map(::unescape)
        if (parts.size < 3) return@mapNotNull null
        DownloadTask(
          id = parts[0],
          updatedAt = parts[1].toLongOrNull() ?: 0L,
          status = TaskStatus.from(parts[2]),
          percent = parts.getOrNull(3)?.toIntOrNull() ?: 0,
          title = parts.getOrNull(4).orEmpty(),
          url = parts.getOrNull(5).orEmpty(),
          formatId = parts.getOrNull(6).orEmpty(),
          conversion = parts.getOrNull(7).orEmpty(),
          thumbnail = parts.getOrNull(8).orEmpty(),
          durationSeconds = parts.getOrNull(9)?.toLongOrNull() ?: 0L,
          message = parts.getOrNull(10).orEmpty(),
          isPlaylist = parts.getOrNull(11) == "1",
          playlistTitle = parts.getOrNull(12).orEmpty(),
        )
      }
      .toList()

  private fun ensureLoaded(context: Context) {
    if (!loaded) refresh(context)
  }

  private fun persist(context: Context) {
    runCatching { prefs(context).edit().putString(KEY_ENTRIES, encode(_entries.value)).apply() }
      .onFailure { Log.w(TAG, "не удалось сохранить задачи: ${it.message}") }
  }

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun now(): Long = System.currentTimeMillis()

  private fun escape(value: String): String =
    value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

  private fun unescape(value: String): String {
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
      val char = value[index]
      if (char == '\\' && index + 1 < value.length) {
        when (val next = value[index + 1]) {
          '\\' -> out.append('\\')
          't' -> out.append('\t')
          'n' -> out.append('\n')
          else -> out.append(next)
        }
        index += 2
      } else {
        out.append(char)
        index++
      }
    }
    return out.toString()
  }


  /** Успешная загрузка уходит в историю — из незавершённых её убираем. */
  fun complete(context: Context, id: String) = remove(context, id)

  fun remove(context: Context, id: String) {
    synchronized(lock) {
      ensureLoaded(context)
      _entries.value = _entries.value.filterNot { it.id == id }
      persist(context)
    }
  }

  fun statusLabel(context: Context, status: TaskStatus): String =
    when (status) {
      TaskStatus.Running -> context.getString(R.string.download_running)
      TaskStatus.Canceled -> context.getString(R.string.task_canceled)
      TaskStatus.Failed -> context.getString(R.string.task_failed)
    }
}
