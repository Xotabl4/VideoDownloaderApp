package org.xotabl4.ytdlpgui.core

import java.util.Locale

/**
 * Порт `downloader.get_formats()` (строки 43–157) и `get_resolution_label()` (18–30)
 * из десктопного `downloader.py`. Логика сохранена один в один:
 * категоризация по кодекам, склейка «только видео» + лучшая аудиодорожка,
 * предпочтение mp4/webm при сортировке, человекочитаемые размеры.
 */
object FormatsParser {
  private const val NO_CODEC = "none"

  /** Аналог `get_resolution_label(width, height)`. */
  fun resolutionLabel(width: Int, height: Int): String {
    if (width == 0 && height == 0) return "Unknown"
    val maxDim = maxOf(width, height)
    return when {
      maxDim >= 3800 -> "2160p 4K"
      maxDim >= 2500 -> "1440p HD"
      maxDim >= 1900 -> "1080p HD"
      maxDim >= 1200 -> "720p"
      maxDim >= 800 -> "480p"
      maxDim >= 600 -> "360p"
      maxDim >= 400 -> "240p"
      maxDim >= 250 -> "144p"
      else -> "${height}p"
    }
  }

  /** Аналог `f"{mb:.1f} MB".replace(".0", "")`, для нуля — `? MB`. */
  fun sizeLabel(bytes: Long): String {
    if (bytes <= 0L) return "? MB"
    val megabytes = bytes / 1024.0 / 1024.0
    val formatted = String.format(Locale.US, "%.1f", megabytes)
    return "${formatted.replace(".0", "")} MB"
  }

  /** `60fps` / `59.94fps`; `null` — источник частоту не сообщил. */
  fun fpsLabel(fps: Double): String? =
    when {
      fps <= 0.0 -> null
      fps % 1.0 == 0.0 -> "${fps.toInt()}fps"
      else -> String.format(Locale.US, "%.2f", fps).trimEnd('0').trimEnd('.') + "fps"
    }

  /** `754` → `12:34`, `3725` → `1:02:05`; пустая строка, если длительность неизвестна. */
  fun formatDuration(seconds: Long): String {
    if (seconds <= 0L) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
      String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
      String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
  }

  fun build(
    title: String,
    raw: List<RawFormat>,
    thumbnail: String = "",
    durationSeconds: Long = 0L,
    playlistTitle: String = "",
    playlistCount: Int = 0,
    isPlaylist: Boolean = false,
  ): Formats {
    val videoAudio = mutableListOf<FormatItem>()
    val videoOnly = mutableListOf<FormatItem>()
    val audioOnly = mutableListOf<FormatItem>()

    for (f in dedupe(raw)) {
      val ext = f.ext?.takeIf { it.isNotBlank() } ?: "unknown"
      val vcodec = f.vcodec?.takeIf { it.isNotBlank() } ?: NO_CODEC
      val acodec = f.acodec?.takeIf { it.isNotBlank() } ?: NO_CODEC
      val filesize = f.filesize ?: f.filesizeApprox ?: 0L
      val height = f.height ?: 0
      val width = f.width ?: 0
      val tbr = f.tbr ?: 0.0
      val fps = f.fps ?: 0.0
      val dynamicRange = f.dynamicRange.orEmpty().takeIf { it.isNotBlank() && it != "SDR" }.orEmpty()

      val hasVideo = vcodec != NO_CODEC
      val hasAudio = acodec != NO_CODEC

      val sizeStr = sizeLabel(filesize)
      val vcodecShort = if (hasVideo) vcodec.substringBefore('.') else "None"
      val acodecShort = if (hasAudio) acodec.substringBefore('.') else "None"
      val codecStr = "V: $vcodecShort / A: $acodecShort"
      // «60fps HDR» — то, чем различаются два 2160p у VK/YouTube.
      val extras = listOfNotNull(fpsLabel(fps), dynamicRange.takeIf { it.isNotBlank() }).joinToString(" ")

      val shortDesc =
        if (hasVideo) {
          "[$ext] ${resolutionLabel(width, height)}${if (extras.isEmpty()) "" else " $extras"} - " +
            "${tbr.toInt()}k - $sizeStr"
        } else {
          "[$ext] Аудио - ${tbr.toInt()}k - $sizeStr"
        }
      val tooltip = "Кодеки: $codecStr\nБитрейт: ${tbr.toInt()}k\nРазмер: $sizeStr"

      val item =
        FormatItem(
          formatId = f.formatId,
          shortDesc = shortDesc,
          tooltip = tooltip,
          ext = ext,
          width = width,
          height = height,
          tbr = tbr,
          hasVideo = hasVideo,
          hasAudio = hasAudio,
          downloadId = f.formatId.orEmpty(),
          rawFilesize = filesize,
          vcodecShort = vcodecShort,
          acodecShort = acodecShort,
          fps = fps,
          dynamicRange = dynamicRange,
        )

      when {
        hasVideo && hasAudio -> videoAudio += item
        hasVideo -> videoOnly += item
        hasAudio -> audioOnly += item
      }
    }

    videoOnly.sortWith(videoSortDesc)
    audioOnly.sortWith(audioSortDesc)
    videoAudio.sortWith(videoSortDesc)

    // «Лучшее из двух миров»: каждый видеоформат + подходящая аудиодорожка (как в downloader.py 112–150)
    val combinedVideo = mutableListOf<FormatItem>()
    for (item in videoOnly) {
      val bestAudio =
        when (item.ext) {
          "mp4" -> audioOnly.firstOrNull { it.ext == "m4a" }
          "webm" -> audioOnly.firstOrNull { it.ext == "webm" || it.ext == "opus" }
          else -> null
        } ?: audioOnly.firstOrNull()

      if (bestAudio != null) {
        val combinedTbr = item.tbr + bestAudio.tbr
        val combinedSize = item.rawFilesize + bestAudio.rawFilesize
        val sizeStr = sizeLabel(combinedSize)
        val codecStr = "V: ${item.vcodecShort} / A: ${bestAudio.acodecShort}"
        combinedVideo +=
          item.copy(
            shortDesc =
              "[${item.ext}] ${resolutionLabel(item.width, item.height)} - ${combinedTbr.toInt()}k - $sizeStr",
            tooltip = "Кодеки: $codecStr\nБитрейт: ${combinedTbr.toInt()}k\nРазмер: $sizeStr",
            downloadId = "${item.formatId}+${bestAudio.formatId}",
          )
      } else {
        combinedVideo +=
          item.copy(
            downloadId = "${item.formatId}+bestaudio/best",
            tooltip = item.tooltip.replace("A: None", "A: Авто (Лучшее доступное)"),
          )
      }
    }

    combinedVideo += videoAudio
    combinedVideo.sortWith(videoSortDesc)

    return Formats(
      title = title,
      videoAudio = combinedVideo,
      videoOnly = videoOnly,
      audioOnly = audioOnly,
      thumbnail = thumbnail,
      durationSeconds = durationSeconds,
      playlistTitle = playlistTitle,
      playlistCount = playlistCount,
      isPlaylist = isPlaylist,
    )
  }

  /**
   * Один и тот же ролик часто отдаётся несколько раз под разными `format_id` с одинаковыми
   * качеством/кодеками: RuTube — `default-*`/`m3u8-*`, VK — `dash_sep-*`/`hls_fmp4-*`/`hls-*`
   * (разница только в битрейте). Ключ — расширение, кодеки, высота, fps и HDR: то, что пользователь
   * реально выбирает. Для аудио высоты нет, поэтому в ключ входит битрейт — иначе все дорожки
   * схлопнулись бы в одну. Из группы остаётся поток с большим битрейтом (при равенстве — не HLS).
   */
  private fun dedupe(raw: List<RawFormat>): List<RawFormat> {
    val seen = LinkedHashMap<String, RawFormat>()
    for (f in raw) {
      val key =
        (
          listOf(f.ext, f.vcodec, f.acodec, f.height, (f.fps ?: 0.0).toInt(), f.dynamicRange) +
            audioKey(f)
        )
          .joinToString("|")
      val existing = seen[key]
      if (existing == null || preferable(f, existing)) seen[key] = f
    }
    return seen.values.toList()
  }

  /** У аудиодорожки нет высоты/кодеков видео — единственное отличие в качестве это битрейт. */
  private fun audioKey(f: RawFormat): List<Any?> {
    val hasVideo = (f.vcodec?.takeIf { it.isNotBlank() } ?: NO_CODEC) != NO_CODEC
    return if (hasVideo) emptyList() else listOf((f.tbr ?: 0.0).toInt())
  }

  /** В группе дублей оставляем поток с большим битрейтом, а при равенстве — не HLS. */
  private fun preferable(candidate: RawFormat, current: RawFormat): Boolean {
    val candidateTbr = candidate.tbr ?: 0.0
    val currentTbr = current.tbr ?: 0.0
    if (candidateTbr != currentTbr) return candidateTbr > currentTbr
    return isHls(current.formatId) && !isHls(candidate.formatId)
  }

  /** `hls-*` / `hls_fmp4-*` (VK) и `m3u8-*` (RuTube) — манифесты, их предпочитаем меньше. */
  private fun isHls(formatId: String?): Boolean {
    val id = formatId.orEmpty()
    return id.contains("hls") || id.contains("m3u8")
  }

  /** Десктоп: mp4 важнее webm, затем высота, затем битрейт (все по убыванию). */
  private val videoSortDesc =
    compareByDescending<FormatItem> { extScore(it.ext, preferred = "mp4", secondary = "webm") }
      .thenByDescending { it.height }
      .thenByDescending { it.tbr }

  /** Десктоп: m4a важнее webm, затем битрейт. */
  private val audioSortDesc =
    compareByDescending<FormatItem> { extScore(it.ext, preferred = "m4a", secondary = "webm") }
      .thenByDescending { it.tbr }

  private fun extScore(ext: String, preferred: String, secondary: String): Int =
    when (ext) {
      preferred -> 2
      secondary -> 1
      else -> 0
    }
}
