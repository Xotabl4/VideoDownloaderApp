package org.xotabl4.ytdlpgui.core

import android.content.Context
import org.xotabl4.ytdlpgui.R

/**
 * Единый перевод технических ошибок yt-dlp в понятные фразы на языке приложения.
 *
 * Зачем: yt-dlp отдаёт вещи вида `ERROR: [youtube] xyz: Sign in to confirm your age`,
 * `Unsupported URL`, `[Errno 103] Software caused connection abort`. Показывать это
 * пользователю бессмысленно — здесь мы раскладываем такие строки по категориям.
 */
object DownloadErrors {
  /** Домены, где скачивание невозможно (DRM/закрытый сервис) — отвечаем, не запуская yt-dlp. */
  private data class SiteRule(val host: String, val messageRes: Int)

  private val siteRules =
    listOf(
      SiteRule("kinopoisk.ru", R.string.error_site_kinopoisk),
      SiteRule("hd.kinopoisk.ru", R.string.error_site_kinopoisk),
      SiteRule("okko.tv", R.string.error_site_drm),
      SiteRule("wink.ru", R.string.error_site_drm),
      SiteRule("start.ru", R.string.error_site_drm),
      SiteRule("kion.ru", R.string.error_site_drm),
      SiteRule("premier.one", R.string.error_site_drm),
      SiteRule("more.tv", R.string.error_site_drm),
      SiteRule("netflix.com", R.string.error_site_drm),
      SiteRule("spotify.com", R.string.error_site_drm),
    )

  /**
   * Если сайт заведомо не поддерживает скачивание — готовый текст причины.
   * Возвращает `null`, когда можно пробовать yt-dlp.
   */
  fun unsupportedSite(context: Context, url: String): String? {
    val host = hostOf(url) ?: return null
    val rule =
      siteRules.firstOrNull { host == it.host || host.endsWith(".${it.host}") } ?: return null
    return context.getString(rule.messageRes)
  }

  /**
   * То же, что [humanize], но с учётом конкретного сайта: у PornHub 403 почти всегда означает
   * возрастной гейт 18+, а у VK `TypeError` из экстрактора плейлистов — ограничение доступа
   * к подборке, а не «неизвестная ошибка».
   */
  fun forUrl(context: Context, url: String, raw: String): String {
    val host = hostOf(url).orEmpty()
    val isPornHub = host == "pornhub.com" || host.endsWith(".pornhub.com")
    if (isPornHub && (raw.contains("403") || raw.contains("Forbidden", ignoreCase = true))) {
      return context.getString(R.string.error_pornhub_age)
    }
    if (isVk(host) && VK_PLAYLIST_BREAKAGES.any { raw.contains(it, ignoreCase = true) }) {
      return context.getString(R.string.error_vk_playlist)
    }
    return humanize(context, raw)
  }

  private fun isVk(host: String): Boolean =
    host == "vk.com" ||
      host.endsWith(".vk.com") ||
      host == "vk.ru" ||
      host.endsWith(".vk.ru") ||
      host == "vkvideo.ru" ||
      host.endsWith(".vkvideo.ru")

  /**
   * Как ломается экстрактор `vk:uservideos`, если VK не отдал список видео:
   * `string indices must be integers` (блокировка/пустой ответ), `'total'`
   * (в ответе нет нужных полей), `cursor data` (страница без данных).
   */
  private val VK_PLAYLIST_BREAKAGES =
    listOf("string indices must be integers", "'total'", "extract cursor data")

  /** Сырой текст ошибки yt-dlp/исключения → понятная фраза на языке приложения. */
  fun humanize(context: Context, raw: String): String {
    val text = cleanUp(raw)
    if (text.isEmpty()) return context.getString(R.string.error_unknown, "—")
    val lower = text.lowercase()

    fun res(id: Int): String = context.getString(id)

    return when {
      lower.contains("unsupported url") ||
        lower.contains("no suitable extractor") ||
        lower.contains("is not a valid url") -> res(R.string.error_site_unsupported)

      lower.contains("drm") || lower.contains("widevine") -> res(R.string.error_site_drm)

      lower.contains("private video") || lower.contains("this video is private") -> res(R.string.error_private)

      // YouTube на «грязных» VPN-адресах требует подтвердить, что запрос не от бота:
      // это не вход в аккаунт, а именно блокировка сервера — лечится сменой сервера VPN.
      lower.contains("not a bot") -> res(R.string.error_youtube_bot)

      (lower.contains("age") && (lower.contains("restrict") || lower.contains("confirm"))) ||
        lower.contains("inappropriate for some users") -> res(R.string.error_age)

      lower.contains("sign in") ||
        lower.contains("log in") ||
        lower.contains("login") ||
        lower.contains("cookies") ||
        lower.contains("members-only") ||
        lower.contains("registered users") ||
        lower.contains("account") -> res(R.string.error_login)

      lower.contains("not available in your country") ||
        lower.contains("geo restriction") ||
        lower.contains("blocked it in your country") ||
        lower.contains("geo-restricted") -> res(R.string.error_geo)

      lower.contains("http error 404") ||
        lower.contains("does not exist") ||
        lower.contains("video unavailable") ||
        lower.contains("is not available") ||
        lower.contains("has been removed") ||
        lower.contains("not found") -> res(R.string.error_not_found)

      lower.contains("http error 403") -> res(R.string.error_forbidden)

      lower.contains("http error 429") || lower.contains("too many requests") -> res(R.string.error_rate_limit)

      Regex("""http error 5\d\d""").containsMatchIn(lower) -> res(R.string.error_server)

      lower.contains("no space left") || lower.contains("enospc") -> res(R.string.error_no_space)

      lower.contains("requested format is not available") -> res(R.string.error_format)

      lower.contains("ffmpeg") || lower.contains("ffprobe") -> res(R.string.error_ffmpeg)

      lower.contains("playlist") && (lower.contains("empty") || lower.contains("no videos")) ->
        res(R.string.error_playlist_empty)

      lower.contains("live event") || lower.contains("is live") || lower.contains("premieres in") ->
        res(R.string.error_live)

      lower.contains("unable to download webpage") ||
        lower.contains("timed out") ||
        lower.contains("timeout") ||
        lower.contains("connection") ||
        lower.contains("name or service not known") ||
        lower.contains("ssl") ||
        lower.contains("errno") ||
        lower.contains("network") -> res(R.string.error_network)

      else -> context.getString(R.string.error_unknown, shorten(text))
    }
  }

  /** `ERROR: [youtube] xyz: текст` → `текст`; WARNING/INFO-строки не считаем ошибкой. */
  fun cleanUp(raw: String): String {
    val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.isEmpty()) return ""
    val errorLine = lines.firstOrNull { it.startsWith("ERROR:", ignoreCase = true) }
    if (errorLine != null) return errorLine.substringAfter("ERROR:").trim()
    // Только предупреждения (например, «2 unavailable videos are hidden») — берём последнюю
    // содержательную строку, но без служебного префикса.
    val meaningful = lines.lastOrNull { !it.startsWith("WARNING", ignoreCase = true) } ?: lines.last()
    return meaningful.removePrefix("WARNING:").trim()
  }

  private fun shorten(text: String): String {
    // Убираем технические префиксы вида `[youtube] 1234:` — пользователю они не нужны.
    val cleaned = text.replace(Regex("""^\[[^\]]+]\s*[^:]{0,64}:\s*"""), "")
    return if (cleaned.length <= 180) cleaned else cleaned.take(177) + "…"
  }

  private fun hostOf(url: String): String? =
    runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull()
      ?: runCatching {
          val withoutScheme = url.trim().substringAfter("://", url.trim())
          withoutScheme.substringBefore('/').substringBefore(':').lowercase()
        }
        .getOrNull()
}
