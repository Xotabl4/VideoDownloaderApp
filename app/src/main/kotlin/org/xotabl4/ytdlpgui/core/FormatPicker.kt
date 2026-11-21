package org.xotabl4.ytdlpgui.core

/** Выбор формата: сначала контейнер, потом качество. */
object FormatPicker {
  /** `bestvideo*+bestaudio/best`, лучшее видео с лучшим аудио. */
  const val BEST_VIDEO_AUDIO = "bestvideo*+bestaudio/best"

  /** Категории выбора; отдельный тип, чтобы core не зависел от UI. */
  enum class Kind {
    VideoAudio,
    VideoOnly,
    AudioOnly,
  }

  /** Порядок контейнеров в чипах. */
  private val CONTAINER_ORDER = listOf("mp4", "webm", "mkv", "m4a", "opus", "mp3", "wav", "flac")

  /** Контейнеры из списка, в нужном порядке. */
  fun containers(items: List<FormatItem>): List<String> =
    items.map { it.ext }.distinct().sortedWith(compareBy({ order(it) }, { it }))

  fun withContainer(items: List<FormatItem>, container: String?): List<FormatItem> =
    if (container.isNullOrBlank()) items else items.filter { it.ext == container }

  /** Лучшее сверху: разрешение, потом битрейт. */
  fun sortedByQuality(items: List<FormatItem>): List<FormatItem> =
    items.sortedWith(compareByDescending<FormatItem> { it.height }.thenByDescending { it.tbr })

  /** Лучший формат из списка. */
  fun best(items: List<FormatItem>): FormatItem? = sortedByQuality(items).firstOrNull()

  /** Подпись качества: 2160p или 128k. */
  fun qualityLabel(item: FormatItem, videoLabel: String = "Видео", audioLabel: String = "Аудио"): String =
    when {
      item.hasVideo && item.height > 0 -> "${item.height}p"
      item.hasVideo -> videoLabel
      item.tbr > 0 -> "${item.tbr.toInt()}k"
      else -> audioLabel
    }

  /** Подпись строки: разрешение, fps, HDR. */
  fun qualityTitle(
    item: FormatItem,
    videoLabel: String = "Видео",
    audioLabel: String = "Аудио",
  ): String {
    val extras =
      listOfNotNull(
        FormatsParser.fpsLabel(item.fps),
        item.dynamicRange.takeIf { it.isNotBlank() },
      )
    val base = qualityLabel(item, videoLabel, audioLabel)
    return if (extras.isEmpty()) base else "$base ${extras.joinToString(" ")}"
  }

  /** Бейдж 4K, 2K, FHD, HD или SD. */
  fun badge(item: FormatItem): String? =
    when {
      !item.hasVideo || item.height <= 0 -> null
      item.height >= 2000 -> "4K"
      item.height >= 1400 -> "2K"
      item.height >= 1000 -> "FHD"
      item.height >= 700 -> "HD"
      item.height >= 400 -> "SD"
      else -> null
    }

  /** Подробности строки: контейнер, кодеки, fps, битрейт, размер. */
  fun detail(item: FormatItem): String {
    val fps = FormatsParser.fpsLabel(item.fps)
    val parts =
      listOfNotNull(
        item.ext,
        "V: ${item.vcodecShort} / A: ${item.acodecShort}",
        fps,
        item.dynamicRange.takeIf { it.isNotBlank() },
        "${item.tbr.toInt()}k",
        FormatsParser.sizeLabel(item.rawFilesize),
      )
    return parts.joinToString(" · ")
  }

  /** Подпись аудиодорожки. */
  fun audioDetail(item: FormatItem): String =
    "A: ${item.acodecShort} · ${FormatsParser.sizeLabel(item.rawFilesize)}"

  /** id для `-f` с отдельными потоками. */
  fun mergedId(video: FormatItem, audio: FormatItem?): String =
    if (audio != null) "${video.downloadId}+${audio.downloadId}" else "${video.downloadId}+bestaudio/best"

  /** id для выбранной категории. */
  fun idFor(
    kind: Kind,
    combined: FormatItem?,
    video: FormatItem?,
    audio: FormatItem?,
  ): String? =
    when (kind) {
      Kind.VideoAudio -> combined?.downloadId ?: video?.let { mergedId(it, audio) }
      Kind.VideoOnly -> video?.let { mergedId(it, audio) }
      Kind.AudioOnly -> audio?.downloadId
    }

  /** Суммарный размер частей, 0 если неизвестен. */
  fun expectedBytes(parts: List<FormatItem?>): Long {
    var total = 0L
    var any = false
    for (part in parts) {
      if (part == null) continue
      if (part.rawFilesize <= 0L) return 0L
      total += part.rawFilesize
      any = true
    }
    return if (any) total else 0L
  }

  /** Размер для выбранной категории. */
  fun bytesFor(
    kind: Kind,
    combined: FormatItem?,
    video: FormatItem?,
    audio: FormatItem?,
  ): Long =
    when (kind) {
      Kind.VideoAudio -> combined?.rawFilesize ?: 0L
      Kind.VideoOnly -> expectedBytes(listOf(video, audio))
      Kind.AudioOnly -> audio?.rawFilesize ?: 0L
    }

  private fun order(ext: String): Int =
    CONTAINER_ORDER.indexOf(ext).let { if (it < 0) CONTAINER_ORDER.size else it }
}
