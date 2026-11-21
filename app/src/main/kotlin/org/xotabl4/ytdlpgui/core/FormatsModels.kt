package org.xotabl4.ytdlpgui.core

/**
 * Один формат из `yt-dlp -J` в «сыром» виде.
 *
 * Соответствует полям, которые читает `get_formats()` в `downloader.py`:
 * `format_id, ext, vcodec, acodec, filesize/filesize_approx, width, height, tbr`.
 * Отдельный слой нужен, чтобы парсер (`FormatsParser`) был чистым Kotlin-кодом
 * и проверялся юнит-тестами без Android/org.json.
 */
data class RawFormat(
  val formatId: String?,
  val ext: String?,
  val vcodec: String?,
  val acodec: String?,
  val filesize: Long?,
  val filesizeApprox: Long?,
  val width: Int?,
  val height: Int?,
  val tbr: Double?,
  /** Кадры в секунду (VK/YouTube отдают `30`, `60`, у HLS — `30.0`). */
  val fps: Double? = null,
  /** `SDR` / `HDR10` / `HLG` и т.п. — нужно, чтобы не путать два 2160p между собой. */
  val dynamicRange: String? = null,
  /** `format_note` yt-dlp: «DASH video», «Audio, high», «HDR»… */
  val formatNote: String? = null,
)

/** Готовый элемент списка форматов (аналог `item` из `downloader.py`). */
data class FormatItem(
  val formatId: String?,
  val shortDesc: String,
  val tooltip: String,
  val ext: String,
  val width: Int,
  val height: Int,
  val tbr: Double,
  val hasVideo: Boolean,
  val hasAudio: Boolean,
  val downloadId: String,
  val rawFilesize: Long,
  val vcodecShort: String,
  val acodecShort: String,
  /** `0.0` — источник не сообщил. */
  val fps: Double = 0.0,
  /** `""` — источник не сообщил; иначе `SDR`, `HDR10`, … */
  val dynamicRange: String = "",
)

/** Результат разбора: три категории, как в десктопной версии, + данные для карточки в библиотеке. */
data class Formats(
  val title: String,
  val videoAudio: List<FormatItem>,
  val videoOnly: List<FormatItem>,
  val audioOnly: List<FormatItem>,
  /** URL миниатюры из `yt-dlp -J` (может быть пустым). */
  val thumbnail: String = "",
  /** Длительность в секундах — для таймкода на превью. */
  val durationSeconds: Long = 0L,
  /** Название плейлиста, если ссылка ведёт на плейлист (иначе пусто). */
  val playlistTitle: String = "",
  /** Сколько видео в плейлисте (0 — не плейлист). */
  val playlistCount: Int = 0,
  /**
   * Ссылка ведёт на плейлист. Отдельный флаг нужен потому, что не все площадки
   * отдают название (VK: `title=null`) — по пустому [playlistTitle] плейлист
   * не отличить от одиночного видео.
   */
  val isPlaylist: Boolean = false,
)
