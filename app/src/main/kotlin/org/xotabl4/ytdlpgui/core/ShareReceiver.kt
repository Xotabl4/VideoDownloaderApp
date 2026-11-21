package org.xotabl4.ytdlpgui.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ссылка из системного «Поделиться». Главный экран подхватывает её и очищает. */
object ShareReceiver {
  data class Event(val id: Long, val url: String)

  private var nextId = 0L
  private val _event = MutableStateFlow<Event?>(null)
  val event: StateFlow<Event?> = _event.asStateFlow()

  fun submit(url: String) {
    if (url.isBlank()) return
    _event.value = Event(nextId++, url)
  }

  fun consume() {
    _event.value = null
  }
}
