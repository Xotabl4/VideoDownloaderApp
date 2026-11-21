package org.xotabl4.ytdlpgui.core

/** Ссылки на каналы VK (`vk.com/video/@name`) — распознаём, но целиком их не качаем. */
object VkChannel {
  private val CHANNEL_URL =
    Regex(
      """//(?:m\.|new\.)?vk(?:video)?\.(?:com|ru)/(?:video/)?@([^/?#]+)""",
      RegexOption.IGNORE_CASE,
    )

  /** Ссылка ведёт на канал (а не на отдельное видео или плейлист). */
  fun matches(url: String): Boolean = CHANNEL_URL.containsMatchIn(url)
}
