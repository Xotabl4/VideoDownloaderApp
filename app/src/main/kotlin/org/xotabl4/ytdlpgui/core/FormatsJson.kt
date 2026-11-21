package org.xotabl4.ytdlpgui.core

import org.json.JSONArray
import org.json.JSONObject

/** Разбор JSON от `yt-dlp -J` в список [RawFormat]. */
object FormatsJson {
  /** Типы, которые yt-dlp отдаёт для плейлистов. */
  private val PLAYLIST_TYPES = setOf("playlist", "multi_video")

  data class Parsed(
    val title: String,
    val formats: List<RawFormat>,
    val thumbnail: String = "",
    val durationSeconds: Long = 0L,
    /** Название плейлиста или пустая строка. */
    val playlistTitle: String = "",
    /** Число видео, 0 для одиночного. */
    val playlistCount: Int = 0,
    /** `-J` вернул плейлист. */
    val isPlaylist: Boolean = false,
    /** Ссылки на ролики плейлиста: форматы запрашиваются у первого рабочего. */
    val entryUrls: List<String> = emptyList(),
  )

  fun parse(json: String): Parsed {
    // Перед JSON бывают предупреждения, поэтому начинаем с первой фигурной скобки.
    val body = json.substring(json.indexOf('{').coerceAtLeast(0))
    val root = JSONObject(body)
    // Плейлист приходит с _type=playlist или multi_video.
    if (root.optString("_type") in PLAYLIST_TYPES) return parsePlaylist(root)
    val title = root.stringOrNull("title") ?: "Unknown Title"
    val thumbnail = root.stringOrNull("thumbnail").orEmpty()
    val durationSeconds = root.longOrNull("duration") ?: 0L

    val array = root.optJSONArray("formats") ?: return Parsed(title, emptyList(), thumbnail, durationSeconds)
    return Parsed(title, formatsFrom(array), thumbnail, durationSeconds)
  }

  /** Плейлист: название и счётчик из корня, форматы из первого ролика с потоками. */
  private fun parsePlaylist(root: JSONObject): Parsed {
    val entries = root.optJSONArray("entries")
    val count = root.longOrNull("playlist_count")?.toInt() ?: entries?.length() ?: 0
    // Часть площадок не отдаёт название, его добирает FormatsRepository.
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
