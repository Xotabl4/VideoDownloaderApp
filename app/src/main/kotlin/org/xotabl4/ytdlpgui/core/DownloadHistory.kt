package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val TAG = "History"
private const val PREFS = "download_history"
private const val KEY_ENTRIES = "entries"

/** Одна запись истории: что скачали, когда, куда и по какому запросу. */
data class HistoryEntry(
  val id: String,
  val title: String,
  val displayPath: String,
  val uri: String,
  val mimeType: String,
  val savedAt: Long,
  val sizeBytes: Long,
  /** Исходная ссылка и формат — чтобы можно было повторить загрузку. */
  val url: String = "",
  val formatId: String = "",
  val conversion: String = "",
  /** URL миниатюры (из `yt-dlp -J`) и длительность — для карточки в библиотеке. */
  val thumbnail: String = "",
  val durationSeconds: Long = 0L,
  /** `true` — запись получена из плейлиста: «Повторить» должно качать его в подпапку. */
  val isPlaylist: Boolean = false,
  /** Название подпапки плейлиста (для повтора). */
  val playlistTitle: String = "",
)

/**
 * История загрузок (на десктопе списка готовых файлов не было).
 *
 * Хранение — одна строка в `SharedPreferences`: записи через `\t`, поля экранируются
 * (`\\`, `\t`, `\n`). Так обходимся без новых зависимостей (`kotlinx-serialization-json`
 * в проекте не подключён), а `encode`/`decode` остаются чистыми функциями и покрыты тестами.
 */
object DownloadHistory {
  /** Сколько записей держим: больше не нужно, а файл настроек не разрастается. */
  const val LIMIT = 100

  private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
  val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

  /** История уже прочитана из настроек — иначе [add] затёр бы старые записи пустым списком. */
  @Volatile private var loaded = false

  private val timeFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

  /** Параллельные загрузки пишут историю одновременно — списки и файл настроек под замком. */
  private val lock = Any()

  /** «08.11 16:45» — компактно, для строки истории. */
  fun formatTime(millis: Long): String = timeFormat.format(Date(millis))

  /** Новые записи сверху; лишние обрезаются до [LIMIT]. */
  fun prepend(entries: List<HistoryEntry>, entry: HistoryEntry): List<HistoryEntry> =
    (listOf(entry) + entries.filterNot { it.id == entry.id }).take(LIMIT)

  fun encode(entries: List<HistoryEntry>): String =
    entries.joinToString(separator = "\n") { entry ->
      listOf(
          entry.id,
          entry.savedAt.toString(),
          entry.sizeBytes.toString(),
          entry.mimeType,
          entry.title,
          entry.displayPath,
          entry.uri,
          entry.url,
          entry.formatId,
          entry.conversion,
          entry.thumbnail,
          entry.durationSeconds.toString(),
          if (entry.isPlaylist) "1" else "0",
          entry.playlistTitle,
        )
        .joinToString(separator = "\t") { escape(it) }
    }

  fun decode(raw: String): List<HistoryEntry> =
    raw.lineSequence()
      .filter { it.isNotBlank() }
      .mapNotNull { line ->
        val parts = line.split('\t').map(::unescape)
        if (parts.size < 7) return@mapNotNull null
        HistoryEntry(
          id = parts[0],
          savedAt = parts[1].toLongOrNull() ?: 0L,
          sizeBytes = parts[2].toLongOrNull() ?: 0L,
          mimeType = parts[3],
          title = parts[4],
          displayPath = parts[5],
          uri = parts[6],
          url = parts.getOrNull(7).orEmpty(),
          formatId = parts.getOrNull(8).orEmpty(),
          conversion = parts.getOrNull(9).orEmpty(),
          thumbnail = parts.getOrNull(10).orEmpty(),
          durationSeconds = parts.getOrNull(11)?.toLongOrNull() ?: 0L,
          isPlaylist = parts.getOrNull(12) == "1",
          playlistTitle = parts.getOrNull(13).orEmpty(),
        )
      }
      .toList()

  /** Читает историю из настроек (вызывается при старте экрана). */
  fun load(context: Context) {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENTRIES, null)
    _entries.value = if (raw.isNullOrBlank()) emptyList() else decode(raw)
    loaded = true
  }

  private fun ensureLoaded(context: Context) {
    if (!loaded) load(context)
  }

  /** Добавляет запись (новая сверху) и сохраняет. */
  fun add(context: Context, entry: HistoryEntry): List<HistoryEntry> =
    synchronized(lock) {
      ensureLoaded(context)
      val updated = prepend(_entries.value, entry)
      _entries.value = updated
      persist(context, updated)
      Log.i(TAG, "added '${entry.title}' → ${entry.displayPath}")
      updated
    }

  /** Запись для только что завершённой загрузки. */
  fun entryFor(
    title: String,
    saved: SavedFile,
    sizeBytes: Long,
    url: String,
    formatId: String,
    conversion: String?,
    thumbnail: String = "",
    durationSeconds: Long = 0L,
    isPlaylist: Boolean = false,
    playlistTitle: String = "",
    now: Long = System.currentTimeMillis(),
  ): HistoryEntry =
    HistoryEntry(
      id = UUID.randomUUID().toString(),
      title = title.ifBlank { saved.title },
      displayPath = saved.displayPath,
      uri = saved.uri,
      mimeType = saved.mimeType,
      savedAt = now,
      sizeBytes = sizeBytes,
      url = url,
      formatId = formatId,
      conversion = conversion.orEmpty(),
      thumbnail = thumbnail,
      durationSeconds = durationSeconds,
      isPlaylist = isPlaylist,
      playlistTitle = playlistTitle,
    )

  /** Удаляет запись и (по флагу) сам файл. Возвращает `true`, если файл удалить удалось. */
  suspend fun remove(context: Context, id: String, deleteFile: Boolean = true): Boolean {
    // Удаление файла — suspend, поэтому оно вне критической секции: сначала список,
    // потом (уже без замка) сам файл.
    val entry =
      synchronized(lock) {
        ensureLoaded(context)
        val found = _entries.value.firstOrNull { it.id == id } ?: return false
        val updated = _entries.value.filterNot { it.id == id }
        _entries.value = updated
        persist(context, updated)
        Log.i(TAG, "removed '${found.title}'")
        found
      }
    val fileDeleted = if (deleteFile) deleteStoredFile(context, entry.uri) else false
    return fileDeleted
  }

  /** Очищает историю; [deleteFiles] удаляет и сами файлы. */
  suspend fun clear(context: Context, deleteFiles: Boolean) {
    val snapshot =
      synchronized(lock) {
        ensureLoaded(context)
        val list = _entries.value
        _entries.value = emptyList()
        persist(context, emptyList())
        Log.i(TAG, "cleared (deleteFiles=$deleteFiles)")
        list
      }
    if (deleteFiles) snapshot.forEach { deleteStoredFile(context, it.uri) }
  }

  /**
   * Удаление файла по uri, который мы же и создали: MediaStore (`content://media/...`),
   * SAF-документ или `file://` из API 24–28.
   */
  private suspend fun deleteStoredFile(context: Context, uri: String): Boolean =
    withContext(Dispatchers.IO) {
      if (uri.isBlank()) return@withContext false
      runCatching {
          val parsed = Uri.parse(uri)
          when {
            parsed.scheme == "file" -> File(parsed.path.orEmpty()).delete()
            DocumentsContract.isDocumentUri(context, parsed) ->
              DocumentsContract.deleteDocument(context.contentResolver, parsed)
            else -> context.contentResolver.delete(parsed, null, null) > 0
          }
        }
        .onFailure { Log.w(TAG, "не удалось удалить файл $uri: ${it.message}") }
        .getOrDefault(false)
    }

  private fun persist(context: Context, entries: List<HistoryEntry>) {
    runCatching {
        context
          .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .edit()
          .putString(KEY_ENTRIES, encode(entries))
          .apply()
      }
      .onFailure { Log.w(TAG, "не удалось сохранить историю: ${it.message}") }
  }

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
}
