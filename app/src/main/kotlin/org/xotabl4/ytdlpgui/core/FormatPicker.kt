package org.xotabl4.ytdlpgui.core

/**
 * Компактный выбор формата «контейнер → качество» вместо длинного плоского списка.
 *
 * Десктоп (`main.py`) и прежний экран ссылки показывали все форматы одной простынёй: в VK Video
 * это 24 строки `[mp4] 1080p - 4567k`, где качество звука видно только после раскрытия строки.
 * Здесь: сначала чипы контейнеров (mp4 / webm / …), затем список качеств с размером — как в
 * мобильных клиентах. Первая строка списка — «Автоматически», то есть лучшее из доступного.
 *
 * Всё — чистые функции над [FormatItem], поэтому покрыто юнит-тестами без Android.
 */
object FormatPicker {
  /** `bestvideo*+bestaudio/best` — «лучшее видео + лучшее аудио» одной строкой. */
  const val BEST_VIDEO_AUDIO = "bestvideo*+bestaudio/best"

  /** Категории выбора. Отдельный тип, чтобы `core` не зависел от UI-энума. */
  enum class Kind {
    VideoAudio,
    VideoOnly,
    AudioOnly,
  }

  /** Предпочтительный порядок контейнеров в чипах; остальные — по алфавиту после них. */
  private val CONTAINER_ORDER = listOf("mp4", "webm", "mkv", "m4a", "opus", "mp3", "wav", "flac")

  /** Контейнеры, встречающиеся в списке, в порядке [CONTAINER_ORDER]. */
  fun containers(items: List<FormatItem>): List<String> =
    items.map { it.ext }.distinct().sortedWith(compareBy({ order(it) }, { it }))

  fun withContainer(items: List<FormatItem>, container: String?): List<FormatItem> =
    if (container.isNullOrBlank()) items else items.filter { it.ext == container }

  /** Лучшее сверху: выше разрешение, затем битрейт (у аудио высота 0 → решает битрейт). */
  fun sortedByQuality(items: List<FormatItem>): List<FormatItem> =
    items.sortedWith(compareByDescending<FormatItem> { it.height }.thenByDescending { it.tbr })

  /** «Лучшее доступное» — первая строка списка «Автоматически». */
  fun best(items: List<FormatItem>): FormatItem? = sortedByQuality(items).firstOrNull()

  /** «2160p» для видео, «128k» для аудио; подписи-заглушки приходят из ресурсов. */
  fun qualityLabel(item: FormatItem, videoLabel: String = "Видео", audioLabel: String = "Аудио"): String =
    when {
      item.hasVideo && item.height > 0 -> "${item.height}p"
      item.hasVideo -> videoLabel
      item.tbr > 0 -> "${item.tbr.toInt()}k"
      else -> audioLabel
    }

  /**
   * Полная подпись строки: «2160p 60fps HDR» / «1080p 30fps» / «265k».
   * Именно этим различаются два 2160p у VK и два 1080p у YouTube.
   */
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

  /** Бейдж качества рядом с разрешением: 4K / 2K / FHD / HD / SD. */
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

  /**
   * Подпись строки списка качеств: контейнер, кодеки, частота кадров, битрейт и ожидаемый размер.
   * Битрейт и fps нужны, чтобы различать строки одного разрешения (например, 1080p 30 и 60 fps).
   */
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

  /** Подпись аудиодорожки в сводке («A: mp4a · 5 MB»). */
  fun audioDetail(item: FormatItem): String =
    "A: ${item.acodecShort} · ${FormatsParser.sizeLabel(item.rawFilesize)}"

  /**
   * id для `yt-dlp -f`: при конвертации видео и звук качаются отдельными потоками
   * (`<video>+<audio>`), без отдельной дорожки — как раньше `+bestaudio/best`.
   */
  fun mergedId(video: FormatItem, audio: FormatItem?): String =
    if (audio != null) "${video.downloadId}+${audio.downloadId}" else "${video.downloadId}+bestaudio/best"

  /** Готовый `-f`-id для выбранной категории; `null` — выбирать нечего. */
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

  /**
   * Ожидаемый размер итогового файла: сумма частей. `0` — размер неизвестен
   * (yt-dlp для части форматов `filesize` не отдаёт).
   */
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

  /** Размер для выбранной категории: «Видео + аудио» = готовый файл, «Только видео» = сумма потоков. */
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
