package org.xotabl4.ytdlpgui.ui.link

import androidx.annotation.StringRes
import org.xotabl4.ytdlpgui.R
import org.xotabl4.ytdlpgui.core.FormatPicker
import org.xotabl4.ytdlpgui.core.Formats

/** Метка «без конвертации». */
const val ORIGINAL_FORMAT = "Оригинал"

/** Контейнеры видео для `--merge-output-format`. */
val VIDEO_CONVERSION_OPTIONS = listOf(ORIGINAL_FORMAT, "mp4", "mkv", "webm", "avi")

/** Форматы аудио для `-x --audio-format`. */
val AUDIO_CONVERSION_OPTIONS = listOf(ORIGINAL_FORMAT, "mp3", "m4a", "wav", "flac")

/** Категории форматов: видео со звуком, только видео, только аудио. */
enum class FormatCategory(@StringRes val labelRes: Int) {
  VideoAudio(R.string.format_video_audio),
  VideoOnly(R.string.format_video_only),
  AudioOnly(R.string.format_audio_only),
  ;

  /** Список целей конвертации для этой категории. */
  val conversionOptions: List<String>
    get() = if (this == AudioOnly) AUDIO_CONVERSION_OPTIONS else VIDEO_CONVERSION_OPTIONS

  /** Категория в терминах [FormatPicker]. */
  val kind: FormatPicker.Kind
    get() =
      when (this) {
        VideoAudio -> FormatPicker.Kind.VideoAudio
        VideoOnly -> FormatPicker.Kind.VideoOnly
        AudioOnly -> FormatPicker.Kind.AudioOnly
      }
}

/**
 * Нужно ли выбирать видео и аудио по отдельности.
 *
 * При конвертации в контейнер нужно два потока: `--merge-output-format` применяется
 * к результату склейки, а не к готовому файлу. Если отдельных потоков нет
 * (RuTube отдаёт только собранные файлы), остаётся категория «Видео + аудио».
 */
fun needsSeparateStreams(conversion: String, formats: Formats): Boolean =
  conversion != ORIGINAL_FORMAT && formats.videoOnly.isNotEmpty()

