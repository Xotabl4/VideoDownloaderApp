package org.xotabl4.ytdlpgui.core

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Каналы VK (`vk.com/video/@name`).
 *
 * yt-dlp разбирает такую страницу через `newCur`, но VK отдаёт её без этого объекта
 * («Unable to extract cursor data»). Поэтому номер владельца и список видео берём сами:
 * страница → `oid`, дальше тот же `al_video.php`, которым пользуется экстрактор.
 */
object VkChannel {
  private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

  /** Сколько видео забираем из канала: дальше это уже десятки запросов к VK. */
  const val MAX_ENTRIES = 1000

  private val CHANNEL_URL =
    Regex(
      """//(?:m\.|new\.)?vk(?:video)?\.(?:com|ru)/(?:video/)?@([^/?#]+)""",
      RegexOption.IGNORE_CASE,
    )

  private val OID_PATTERNS =
    listOf(
      Regex("""newCur\s*=\s*\{[^}]{0,400}?["']?oid["']?\s*:\s*(-?\d+)""", RegexOption.DOT_MATCHES_ALL),
      Regex("""["']oid["']\s*:\s*(-?\d+)"""),
      Regex("""["']owner_id["']\s*:\s*(-?\d+)"""),
      Regex("""video(-?\d+)_\d{1,20}"""),
    )

  private val PANEL_TITLE =
    Regex(
      """class=["'][^"']*VideoInfoPanel__title[^"']*["'][^>]*>(.*?)<""",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
  private val PAGE_TITLE =
    Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

  /** Разобранный канал. */
  data class Info(val ownerId: String, val title: String, val total: Int, val entries: List<String>)

  sealed interface Result {
    data class Ok(val info: Info) : Result

    /** `code=4` — ограничение доступа (в том числе блокировка РКН). */
    data object Blocked : Result

    /** `code=3` — нужен вход в аккаунт. */
    data object LoginRequired : Result

    /** Не разобрали ответ; решение принимает общий маппер ошибок. */
    data object Unknown : Result
  }

  @Volatile private var lastUrl: String = ""
  @Volatile private var lastInfo: Info? = null

  fun matches(url: String): Boolean = CHANNEL_URL.containsMatchIn(url)

  /** Видео канала, уже разобранные для этой ссылки (для скачивания). */
  fun cachedEntries(url: String): List<String> =
    if (lastUrl == url.trim()) lastInfo?.entries.orEmpty() else emptyList()

  /** Разбирает канал: имя, число видео и список ссылок (`limit` — сколько видео забрать). */
  fun resolve(context: Context, url: String, limit: Int = MAX_ENTRIES): Result {
    val key = url.trim()
    lastInfo?.let { if (lastUrl == key && it.entries.size >= limit) return Result.Ok(it) }
    val page =
      try {
        page(key)
      } catch (e: LoginException) {
        debug(context, "login wall")
        return Result.LoginRequired
      } catch (e: Exception) {
        debug(context, "page failed: ${e.message}")
        return Result.Unknown
      }
    val ownerId = OID_PATTERNS.firstNotNullOfOrNull { it.find(page)?.groupValues?.getOrNull(1) }
    if (ownerId.isNullOrBlank()) {
      debug(context, "owner id not found, page=${page.length} chars")
      // Копия страницы для разбора: видно, что VK отдал вместо конфигурации.
      runCatching {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, "vk_page.html").writeText(page.take(400_000))
      }
      return Result.Unknown
    }
    val title = channelTitle(page).ifBlank { ownerId }
    val entries = mutableSetOf<String>()
    var total = 0
    var offset = 0
    try {
      while (entries.size < limit) {
        val section = requestSection(ownerId, offset) ?: break
        total = maxOf(total, section.total)
        if (section.ids.isEmpty()) break
        section.ids.forEach { (videoOwner, videoId) ->
          entries += "https://vk.com/video${videoOwner}_$videoId"
        }
        if (section.count >= section.total || section.count <= offset) break
        offset = section.count
      }
    } catch (e: BlockedException) {
      debug(context, "blocked: $ownerId")
      return Result.Blocked
    } catch (e: LoginException) {
      debug(context, "login required: $ownerId")
      return Result.LoginRequired
    } catch (e: Exception) {
      debug(context, "entries failed: ${e.message} (owner=$ownerId)")
      return Result.Unknown
    }
    if (entries.isEmpty()) {
      debug(context, "no entries: owner=$ownerId, total=$total")
      return Result.Unknown
    }
    val info = Info(ownerId, title, if (total > 0) total else entries.size, entries.toList())
    if (limit >= MAX_ENTRIES) {
      lastUrl = key
      lastInfo = info
    }
    return Result.Ok(info)
  }

  private class BlockedException : Exception()

  private class LoginException : Exception()

  /** Страница канала: нужен только номер владельца и название. */
  private fun page(url: String): String {
    var lastError = "no candidates"
    for (candidate in pageCandidates(url)) {
      try {
        return fetchPage(candidate)
      } catch (e: LoginException) {
        throw e
      } catch (e: Exception) {
        lastError = "${candidate.take(70)}: ${e.message}"
      }
    }
    error(lastError)
  }

  /** VK отдаёт `vk.com/video/@name` редиректом на вход — пробуем другие адреса той же страницы. */
  private fun pageCandidates(url: String): List<String> {
    val name = CHANNEL_URL.find(url)?.groupValues?.getOrNull(1) ?: return listOf(url)
    return listOf(
        "https://vkvideo.ru/@$name",
        "https://vk.com/video/@$name?section=all",
        "https://m.vk.com/video/@$name",
        url,
      )
      .distinct()
  }

  private fun fetchPage(url: String): String {
    val connection =
      (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 10_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
      }
    return try {
      if (connection.responseCode !in 200..299) error("VK page: HTTP ${connection.responseCode}")
      // Завернулись на страницу входа — значит, разбирать нечего.
      val host = connection.url.host.orEmpty()
      if (host.contains("login.vk") || host.contains("way.vk")) throw LoginException()
      VkPlaylist.decode(connection.inputStream.use { it.readBytes() })
    } finally {
      connection.disconnect()
    }
  }

  private data class Section(val count: Int, val total: Int, val ids: List<Pair<Long, Long>>)

  /** `al_video.php` → список видео канала (пара «владелец, id» на ролик). */
  private fun requestSection(ownerId: String, offset: Int): Section? {
    val form =
      listOf(
          "act" to "load_videos_silent",
          "offset" to offset.toString(),
          "oid" to ownerId,
          "section" to "all",
          "al" to "1",
        )
        .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    val connection =
      (URL("https://vk.com/al_video.php").openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 10_000
        requestMethod = "POST"
        doOutput = true
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Referer", "https://vk.com/al_video.php")
        setRequestProperty("X-Requested-With", "XMLHttpRequest")
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      }
    return try {
      connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
      if (connection.responseCode !in 200..299) error("VK al_video: HTTP ${connection.responseCode}")
      parseSection(VkPlaylist.decode(connection.inputStream.use { it.readBytes() }))
    } finally {
      connection.disconnect()
    }
  }

  /** Ответ `al_video.php`: код 0 — данные, 3 — вход, 4 — ограничение доступа. */
  private fun parseSection(body: String): Section? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val payload = root.optJSONArray("payload") ?: return null
    when (payload.optString(0, "")) {
      "3" -> throw LoginException()
      "4" -> throw BlockedException()
      "0" -> Unit
      else -> return null
    }
    val section = payload.optJSONArray(1)?.optJSONObject(0)?.optJSONObject("all") ?: return null
    val count = section.optInt("count", 0)
    val total = section.optInt("total", count)
    val ids = mutableListOf<Pair<Long, Long>>()
    val list = section.optJSONArray("list")
    if (list != null) {
      for (index in 0 until list.length()) {
        val item = list.optJSONArray(index) ?: continue
        val owner = item.optLong(0, Long.MIN_VALUE)
        val id = item.optLong(1, Long.MIN_VALUE)
        if (owner != Long.MIN_VALUE && id != Long.MIN_VALUE) ids += owner to id
      }
    }
    return Section(count, total, ids)
  }

  /** Название канала: панель видео, иначе `<title>`. */
  private fun channelTitle(page: String): String {
    val raw =
      PANEL_TITLE.find(page)?.groupValues?.getOrNull(1)
        ?: PAGE_TITLE.find(page)?.groupValues?.getOrNull(1)
        ?: return ""
    return unescape(raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim())
      .substringBefore('|')
      .trim()
  }

  private fun unescape(text: String): String =
    text
      .replace("&quot;", "\"")
      .replace("&#039;", "'")
      .replace("&#39;", "'")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&amp;", "&")

  /** Диагностика: файл читается через adb, когда канал не разбирается. */
  private fun debug(context: Context, message: String) {
    android.util.Log.w("VkChannel", message)
    runCatching {
      val dir = context.getExternalFilesDir(null) ?: context.filesDir
      File(dir, "vk_channel_debug.txt").appendText("${System.currentTimeMillis()}: $message\n")
    }
  }
}
