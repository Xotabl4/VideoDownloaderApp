package org.xotabl4.ytdlpgui.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Разбор `yt-dlp -J` (JSON) в [RawFormat]-список.
 *
 * Аналог десктопного `json.loads(result.stdout)` в `downloader.get_formats()`,
 * только без `subprocess`: JSON приходит в `YoutubeDLResponse.out`.
 */
object FormatsJson {
  /** Виды контейнеров yt-dlp, которые ведут себя как плейлист. */
  private val PLAYLIST_TYPES = setOf("playlist", "multi_video")

  data class Parsed(
    val title: String,
    val formats: List<RawFormat>,
    val thumbnail: String = "",
    val durationSeconds: Long = 0L,
    /** Название плейлиста, если ссылка ведёт на плейлист (иначе пусто). */
    val playlistTitle: String = "",
    /** Сколько видео в плейлисте (0 — не плейлист). */
    val playlistCount: Int = 0,
    /** `true`, если `-J` вернул плейлист (`_type=playlist`). */
    val isPlaylist: Boolean = false,
    /**
     * URL роликов плейлиста — у первого доступного отдельно запрашиваем форматы
     * (`--flat-playlist` их не отдаёт). Список нужен, чтобы пережить битый первый ролик.
     */
    val entryUrls: List<String> = emptyList(),
  )

  fun parse(json: String): Parsed {
    // На случай предупреждений/мусора перед JSON — берём от первой `{`.
    val body = json.substring(json.indexOf('{').coerceAtLeast(0))
    val root = JSONObject(body)
    // Плейлист: `-J` отдаёт `_type=playlist` (или `multi_video` для страниц с подборкой)
    // и `entries` с полными форматами.
    if (root.optString("_type") in PLAYLIST_TYPES) return parsePlaylist(root)
    val title = root.stringOrNull("title") ?: "Unknown Title"
    val thumbnail = root.stringOrNull("thumbnail").orEmpty()
    val durationSeconds = root.longOrNull("duration") ?: 0L

    val array = root.optJSONArray("formats") ?: return Parsed(title, emptyList(), thumbnail, durationSeconds)
    return Parsed(title, formatsFrom(array), thumbnail, durationSeconds)
  }

  /**
   * Плейлист: название и количество берём из корня, а форматы — из первого видео,
   * у которого они есть (набор качеств у роликов одинаковый, для выбора этого хватает).
   * Названия может не быть вовсе (VK кладёт `title=null`), тогда его добирает
   * `FormatsRepository` — из API RuTube или из `al_video.php` у VK.
   */
  private fun parsePlaylist(root: JSONObject): Parsed {
    val entries = root.optJSONArray("entries")
    val count = root.longOrNull("playlist_count")?.toInt() ?: entries?.length() ?: 0
    // RuTube не отдаёт название плейлиста — оставляем пусто, его доберём из API.
    val title = root.stringOrNull("title").orEmpty()

    var formats: List<RawFormat> = emptyList()
    var thumbnail = ""
    var durationSeconds = 0L
    val entryUrls = ArrayList<String>(5)
    if (entries != null) {
      for (i in 0 until entries.length()) {
        val entry = entries.optJSONObject(i) ?: continue
        (entry.stringOrNull("webpage_url") ?: entry.stringOrNull("url"))
          ?.takeIf { it.startsWith("http") }
          ?.let { if (it !in entryUrls) entryUrls += it }
        val entryFormats = entry.optJSONArray("formats")
        if (formats.isEmpty() && entryFormats != null && entryFormats.length() > 0) {
          formats = formatsFrom(entryFormats)
          thumbnail = entry.stringOrNull("thumbnail").orEmpty()
          durationSeconds = entry.longOrNull("duration") ?: 0L
        }
      }
    }
    return Parsed(
      title = title.ifBlank { "Playlist" },
      formats = formats,
      thumbnail = thumbnail,
      durationSeconds = durationSeconds,
      playlistTitle = title,
      playlistCount = count,
      isPlaylist = true,
      entryUrls = entryUrls,
    )
  }

  private fun formatsFrom(array: JSONArray): List<RawFormat> {
    val formats = ArrayList<RawFormat>(array.length())
    for (i in 0 until array.length()) {
      val o = array.optJSONObject(i) ?: continue
      formats +=
        RawFormat(
          formatId = o.stringOrNull("format_id"),
          ext = o.stringOrNull("ext"),
          vcodec = o.stringOrNull("vcodec"),
          acodec = o.stringOrNull("acodec"),
          filesize = o.longOrNull("filesize"),
          filesizeApprox = o.longOrNull("filesize_approx"),
          width = o.intOrNull("width"),
          height = o.intOrNull("height"),
          tbr = o.doubleOrNull("tbr"),
          fps = o.doubleOrNull("fps"),
          dynamicRange = o.stringOrNull("dynamic_range"),
          formatNote = o.stringOrNull("format_note"),
        )
    }
    return formats
  }

  private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

  private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)

  private fun JSONObject.intOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)

  private fun JSONObject.doubleOrNull(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key)
}
