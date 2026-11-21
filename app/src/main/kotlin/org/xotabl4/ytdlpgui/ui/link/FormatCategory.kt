package org.xotabl4.ytdlpgui.ui.link

import androidx.annotation.StringRes
import org.xotabl4.ytdlpgui.R
import org.xotabl4.ytdlpgui.core.FormatPicker
import org.xotabl4.ytdlpgui.core.Formats

/** Значение «без конвертации» — как `Оригинал` в десктопном `main.py`. */
const val ORIGINAL_FORMAT = "Оригинал"

/** Контейнеры видео (десктоп: `--merge-output-format`); `avi` помечен как рискованный на Android. */
val VIDEO_CONVERSION_OPTIONS = listOf(ORIGINAL_FORMAT, "mp4", "mkv", "webm", "avi")

/** Аудио-форматы (десктоп: `-x --audio-format`). */
val AUDIO_CONVERSION_OPTIONS = listOf(ORIGINAL_FORMAT, "mp3", "m4a", "wav", "flac")

/** Три кнопки категорий десктопной версии (`main.py`): видео+аудио, только видео, только аудио. */
enum class FormatCategory(@StringRes val labelRes: Int) {
  VideoAudio(R.string.format_video_audio),
  VideoOnly(R.string.format_video_only),
  AudioOnly(R.string.format_audio_only),
  ;

  /** Набор «Конвертировать в» как на десктопе (main.py:354–357). */
  val conversionOptions: List<String>
    get() = if (this == AudioOnly) AUDIO_CONVERSION_OPTIONS else VIDEO_CONVERSION_OPTIONS

  /** Представление категории для чистой логики выбора ([FormatPicker]). */
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
 * Так при любой конверсии, кроме «Оригинал»: пользователь сам ставит галочку на видео и на
 * аудиодорожке, а ffmpeg склеивает их в выбранный контейнер (иначе `--merge-output-format`
 * применялся бы к случайному готовому файлу). Исключение — источники без отдельных потоков
 * (RuTube отдаёт только склеенные файлы): там выбирать нечего, поэтому список «Видео + аудио»
 * остаётся доступным.
 */
fun needsSeparateStreams(conversion: String, formats: Formats): Boolean =
  conversion != ORIGINAL_FORMAT && formats.videoOnly.isNotEmpty()

