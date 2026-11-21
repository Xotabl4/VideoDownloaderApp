package org.xotabl4.ytdlpgui.core

/** Формат из `yt-dlp -J` до обработки. Поля совпадают с ключами JSON. */
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
  /** Кадры в секунду, у HLS бывает дробным. */
  val fps: Double? = null,
  /** SDR, HDR10, HLG и подобное. */
  val dynamicRange: String? = null,
  /** Подпись потока из yt-dlp (`format_note`). */
  val formatNote: String? = null,
)

/** Формат, готовый к показу в списке. */
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
  /** 0.0, если источник не сообщил. */
  val fps: Double = 0.0,
  /** Пусто, если источник не сообщил. */
  val dynamicRange: String = "",
)

/** Результат разбора: категории форматов и данные для карточки в библиотеке. */
data class Formats(
  val title: String,
  val videoAudio: List<FormatItem>,
  val videoOnly: List<FormatItem>,
  val audioOnly: List<FormatItem>,
  /** Миниатюра из метаданных, может быть пустой. */
  val thumbnail: String = "",
  /** Длительность для таймкода на превью. */
  val durationSeconds: Long = 0L,
  /** Название плейлиста или пустая строка. */
  val playlistTitle: String = "",
  /** Число видео в плейлисте, 0 для одиночного. */
  val playlistCount: Int = 0,
  /** Ссылка ведёт на плейлист. Отдельный флаг нужен, потому что название есть не у всех площадок. */
  val isPlaylist: Boolean = false,
)
