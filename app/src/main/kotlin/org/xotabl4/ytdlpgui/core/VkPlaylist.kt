package org.xotabl4.ytdlpgui.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Метаданные плейлиста VK, которых нет в `yt-dlp -J`.
 *
 * Для `vk.com/video/playlist/<oid>_<id>` yt-dlp отдаёт `_type=playlist`, но `title`
 * и `playlist_count` пустые: страница собирается скриптами. Внутренний AJAX-метод
 * `al_video.php` отдаёт `total` и имя владельца, этого хватает для подпапки и счётчика.
 *
 * Ответ `code=4` означает ограничение по требованию Роскомнадзора; yt-dlp такой ответ
 * не разбирает и падает с `TypeError: string indices must be integers`.
 */
object VkPlaylist {
  /** `vk.com/video/playlist/-174476437_2`, `vkvideo.ru/playlist/-51890028_-2`, `vk.ru/...`. */
  private val PLAYLIST_URL =
    Regex(
      """//(?:m\.|new\.)?vk(?:video)?\.(?:com|ru)/(?:video/)?playlist/(-?\d+)_(-?\d+)""",
      RegexOption.IGNORE_CASE,
    )

  private val OWNER_LINK =
    Regex(
      """<a[^>]*class=['"][^'"]*VideoCard__owner[^'"]*['"][^>]*>(.*?)</a>""",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

  private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; M2101K6G) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

  /** Разобранная ссылка: `ownerId` = `-174476437`, `sectionId` = `2`. */
  data class Ref(val ownerId: String, val sectionId: String)

  sealed interface Info {
    /** `code=0`: плейлист доступен. */
    data class Ok(val owner: String, val total: Int, val sectionId: String) : Info

    /** `code=4` (`blank.php?rkn=…`) — доступ ограничен на уровне страны. */
    data object Blocked : Info

    /** `code=3` — VK требует вход в аккаунт. */
    data object LoginRequired : Info

    /** Сеть/формат ответа: решение принимает обычный маппер ошибок yt-dlp. */
    data object Unknown : Info
  }

  /** Ссылка ведёт на плейлист VK (`vk.com/video/playlist/…`, `vkvideo.ru/playlist/…`). */
  fun matches(url: String): Boolean = parse(url) != null

  fun parse(url: String): Ref? {
    val match = PLAYLIST_URL.find(url) ?: return null
    val ownerId = match.groupValues[1]
    val sectionId = match.groupValues[2]
    if (ownerId.isEmpty() || sectionId.isEmpty()) return null
    return Ref(ownerId, sectionId)
  }

  /** Запрашивает `al_video.php` и разбирает ответ. Ошибки сети → [Info.Unknown]. */
  fun fetch(url: String): Info {
    val ref = parse(url) ?: return Info.Unknown
    val body = runCatching { decode(request(ref)) }.getOrNull() ?: return Info.Unknown
    return runCatching { parsePayload(body, ref.sectionId) }.getOrDefault(Info.Unknown)
  }

  /**
   * VK отдаёт `al_video.php` то в UTF-8, то в windows-1251 (зависит от эксперимента
   * на стороне сервера). Без этого имя владельца превращалось в `?????` и попадало
   * в название папки.
   */
  internal fun decode(bytes: ByteArray): String {
    val utf8 = String(bytes, Charsets.UTF_8)
    return if ('\uFFFD' in utf8) String(bytes, charset("windows-1251")) else utf8
  }

  /** `vk.com/al_video.php` — тот же запрос, что делает экстрактор `vk:uservideos`. */
  private fun request(ref: Ref): ByteArray {
    val form =
      listOf(
          "act" to "load_videos_silent",
          "offset" to "0",
          "oid" to ref.ownerId,
          "section" to "playlist_${ref.sectionId}",
          "al" to "1",
        )
        .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    val connection =
      (URL("https://vk.com/al_video.php").openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 8_000
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Referer", "https://vk.com/al_video.php")
        setRequestProperty("X-Requested-With", "XMLHttpRequest")
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      }
    return try {
      connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
      if (connection.responseCode != HttpURLConnection.HTTP_OK) {
        error("VK al_video: HTTP ${connection.responseCode}")
      }
      connection.inputStream.use { it.readBytes() }
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Ответ `al_video.php` = `{"payload": ["<code>", <data>]}`:
   * `code=0` → `data[0]["playlist_<id>"]` с `list/count/total`;
   * `code=4` → `data = ["/blank.php?rkn=…", "0"]` (блокировка);
   * `code=3` → нужен вход.
   */
  internal fun parsePayload(body: String, sectionId: String): Info {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return Info.Unknown
    val payload = root.optJSONArray("payload") ?: return Info.Unknown
    val code = payload.optString(0, "")
    return when (code) {
      "3" -> Info.LoginRequired
      "4" -> Info.Blocked
      "0" -> {
        val section =
          payload.optJSONArray(1)?.optJSONObject(0)?.optJSONObject("playlist_$sectionId")
            ?: return Info.Unknown
        val total = section.optInt("total", section.optInt("count", 0))
        Info.Ok(owner = ownerName(section.optJSONArray("list")), total = total, sectionId = sectionId)
      }
      else -> Info.Unknown
    }
  }

  /** Имя владельца плейлиста: в карточках видео оно лежит в HTML-ссылке `VideoCard__owner…`. */
  private fun ownerName(list: JSONArray?): String {
    if (list == null) return ""
    for (index in 0 until minOf(list.length(), 3)) {
      val item = list.optJSONArray(index) ?: continue
      for (field in 0 until item.length()) {
        val text = item.optString(field, "")
        if (!text.contains("VideoCard__owner")) continue
        val name = OWNER_LINK.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (name.isNotEmpty()) return unescapeHtml(name)
      }
    }
    return ""
  }

  private fun unescapeHtml(text: String): String =
    text
      .replace("&quot;", "\"")
      .replace("&#039;", "'")
      .replace("&#39;", "'")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&amp;", "&")
      .trim()
}
