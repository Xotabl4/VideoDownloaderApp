package org.xotabl4.ytdlpgui.ui.link

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xotabl4.ytdlpgui.core.DownloadManager
import org.xotabl4.ytdlpgui.core.FormatItem
import org.xotabl4.ytdlpgui.core.FormatPicker
import org.xotabl4.ytdlpgui.core.Formats
import org.xotabl4.ytdlpgui.core.FormatsRepository

/** Что выбрано в каждой категории: контейнер (чипы mp4/webm/…) и качество. */
data class FormatSelection(
  val containerVideoAudio: String? = null,
  val containerVideo: String? = null,
  val containerAudio: String? = null,
  val combined: FormatItem? = null,
  val video: FormatItem? = null,
  val audio: FormatItem? = null,
) {
  fun containerFor(category: FormatCategory): String? =
    when (category) {
      FormatCategory.VideoAudio -> containerVideoAudio
      FormatCategory.VideoOnly -> containerVideo
      FormatCategory.AudioOnly -> containerAudio
    }

  fun itemFor(category: FormatCategory): FormatItem? =
    when (category) {
      FormatCategory.VideoAudio -> combined
      FormatCategory.VideoOnly -> video
      FormatCategory.AudioOnly -> audio
    }

  fun withContainer(category: FormatCategory, container: String?): FormatSelection =
    when (category) {
      FormatCategory.VideoAudio -> copy(containerVideoAudio = container)
      FormatCategory.VideoOnly -> copy(containerVideo = container)
      FormatCategory.AudioOnly -> copy(containerAudio = container)
    }

  fun withItem(category: FormatCategory, item: FormatItem?): FormatSelection =
    when (category) {
      FormatCategory.VideoAudio -> copy(combined = item)
      FormatCategory.VideoOnly -> copy(video = item)
      FormatCategory.AudioOnly -> copy(audio = item)
    }
}

/** Состояние экрана «ссылка → форматы». */
sealed interface LinkUiState {
  data object Idle : LinkUiState

  data object Loading : LinkUiState

  data class Success(val formats: Formats) : LinkUiState

  data class Error(val message: String) : LinkUiState
}

class LinkViewModel(application: Application) : AndroidViewModel(application) {
  private val context: Context = application.applicationContext

  private val _url = MutableStateFlow("")
  val url: StateFlow<String> = _url.asStateFlow()

  private val _state = MutableStateFlow<LinkUiState>(LinkUiState.Idle)
  val state: StateFlow<LinkUiState> = _state.asStateFlow()

  private val _category = MutableStateFlow(FormatCategory.VideoAudio)
  val category: StateFlow<FormatCategory> = _category.asStateFlow()

  /** Выбранный формат в каждой категории. */
  private val _selection = MutableStateFlow(FormatSelection())
  val selection: StateFlow<FormatSelection> = _selection.asStateFlow()

  /** Выбор формата; повторный клик по тому же формату снимает выбор. */
  fun selectFormat(category: FormatCategory, item: FormatItem?) {
    val current = _selection.value.itemFor(category)
    val next = if (current?.downloadId == item?.downloadId) null else item
    _selection.value = _selection.value.withItem(category, next)
  }

  /** Смена контейнера (чипы mp4/webm/…): сразу выбираем лучшее качество в нём. */
  fun selectContainer(category: FormatCategory, container: String?) {
    val best = FormatPicker.best(FormatPicker.withContainer(itemsFor(category), container))
    _selection.value = _selection.value.withContainer(category, container).withItem(category, best)
  }

  private var fetchJob: Job? = null

  fun onUrlChange(value: String) {
    if (value == _url.value) return
    _url.value = value
    // Любая правка ссылки сбрасывает результат прошлой загрузки.
    // Активную загрузку reset() не трогает.
    DownloadManager.reset()
  }

  fun onClear() {
    fetchJob?.cancel()
    _url.value = ""
    _state.value = LinkUiState.Idle
    _selection.value = FormatSelection()
    // Иначе на пустом экране остаётся «Готово: <старое видео>» с кнопкой «Открыть».
    DownloadManager.reset()
  }

  fun selectCategory(category: FormatCategory) {
    if (_category.value != category) {
      _category.value = category
      // При смене категории конвертация сбрасывается в «Оригинал».
      _conversion.value = ORIGINAL_FORMAT
    }
  }

  /** Цель конвертации. */
  private val _conversion = MutableStateFlow(ORIGINAL_FORMAT)
  val conversion: StateFlow<String> = _conversion.asStateFlow()

  fun setConversion(value: String) {
    _conversion.value = value
    val formats = (_state.value as? LinkUiState.Success)?.formats ?: return
    if (!needsSeparateStreams(value, formats)) return
    // Для склейки в другой контейнер нужны отдельные потоки: «Видео + аудио» здесь недоступно.
    if (_category.value == FormatCategory.VideoAudio) {
      _category.value =
        if (formats.videoOnly.isNotEmpty()) FormatCategory.VideoOnly else FormatCategory.AudioOnly
    }
  }

  fun fetch() = fetch(_url.value)

  private fun fetch(rawUrl: String) {
    val url = rawUrl.trim()
    if (url.isEmpty()) return
    fetchJob?.cancel()
    fetchJob =
      viewModelScope.launch {
        _state.value = LinkUiState.Loading
        _state.value =
          try {
            LinkUiState.Success(FormatsRepository.fetch(context, url))
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            LinkUiState.Error(humanize(e))
          }
        (_state.value as? LinkUiState.Success)?.let { _selection.value = defaultSelection(it.formats) }
      }
  }

  /** По умолчанию везде выбрано лучшее доступное — это и есть строка «Автоматически». */
  private fun defaultSelection(formats: Formats): FormatSelection =
    FormatSelection(
      combined = FormatPicker.best(formats.videoAudio),
      video = FormatPicker.best(formats.videoOnly),
      audio = FormatPicker.best(formats.audioOnly),
    )

  private fun itemsFor(category: FormatCategory): List<FormatItem> {
    val formats = (_state.value as? LinkUiState.Success)?.formats ?: return emptyList()
    return when (category) {
      FormatCategory.VideoAudio -> formats.videoAudio
      FormatCategory.VideoOnly -> formats.videoOnly
      FormatCategory.AudioOnly -> formats.audioOnly
    }
  }

  private fun humanize(e: Exception): String {
    val message = e.message.orEmpty().trim()
    return when {
      message.isEmpty() -> e::class.java.simpleName
      message.contains("ERROR:") -> message.substringAfter("ERROR:").trim()
      else -> message
    }
  }
}
