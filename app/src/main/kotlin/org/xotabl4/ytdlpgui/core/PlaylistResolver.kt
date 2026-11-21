package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Серия плейлиста: что качать и как подписать в «шторке». */
data class PlaylistEntry(val url: String, val title: String)

/**
 * Список серий плейлиста для параллельной загрузки.
 *
 * `yt-dlp --flat-playlist --print` отдаёт по строке на серию и не разворачивает форматы,
 * поэтому список приходит быстро. Если площадка список не отдала (или он пуст),
 * [DownloadManager] качает плейлист одним процессом, как раньше.
 */
object PlaylistResolver {
  private const val TAG = "PlaylistResolver"

  /** Метка, чтобы отличить служебные строки от обычного вывода yt-dlp. */
  const val ENTRY_PREFIX = "@@entry\t"

  /** `--print`: ссылка на серию, её `webpage_url` (если есть) и название. */
  const val ENTRY_TEMPLATE = "@@entry\t%(url)s\t%(webpage_url|)s\t%(title)s"

  suspend fun resolve(context: Context, url: String): List<PlaylistEntry> =
    withContext(Dispatchers.IO) {
      // PornHub и каналы VK разбираются своими путями — плейлиста тут нет.
      if (PhCookies.isPornHub(url) || VkChannel.matches(url)) return@withContext emptyList()

      val request =
        YoutubeDLRequest(url).apply {
          // Не разворачиваем каждый ролик: нужен только список серий.
          addOption("--flat-playlist")
          addOption("--ignore-errors")
          addOption("--no-warnings")
          addOption("--print", ENTRY_TEMPLATE)
          if (CurlImpersonate.wantedFor(url) && CurlImpersonate.ensure(context)) {
            addOption("--impersonate", "chrome")
          }
        }
      val out =
        try {
          YoutubeDL.getInstance().execute(request).out
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "не удалось получить список серий: ${e.message?.take(200)}")
          return@withContext emptyList()
        }
      parseLines(url, out.lines())
    }

  /** Разбор строк `--print`: номер серии берём по порядку, дубликаты ссылок отбрасываем. */
  fun parseLines(baseUrl: String, lines: List<String>): List<PlaylistEntry> {
    val result = LinkedHashMap<String, PlaylistEntry>()
    lines.forEach { line ->
      if (!line.startsWith(ENTRY_PREFIX)) return@forEach
      val parts = line.removePrefix(ENTRY_PREFIX).split('\t', limit = 3)
      if (parts.size < 3) return@forEach
      val url = entryUrl(baseUrl, rawUrl = parts[0], webpageUrl = parts[1]) ?: return@forEach
      val title = parts[2].trim()
      result.putIfAbsent(url, PlaylistEntry(url, title))
    }
    return result.values.toList()
  }

  /**
   * Ссылка на серию. В flat-режиме часть площадок отдаёт только id ролика,
   * поэтому достраиваем адрес по хосту исходного плейлиста.
   */
  fun entryUrl(baseUrl: String, rawUrl: String, webpageUrl: String): String? {
    webpageUrl.trim().takeIf { it.startsWith("http") }?.let {
      return it
    }
    val direct = rawUrl.trim().takeIf { it.isNotBlank() && it != "NA" } ?: return null
    if (direct.startsWith("http")) return direct

    val host =
      runCatching { URI(baseUrl).host.orEmpty() }.getOrDefault("").substringAfterLast("@")
    return when {
      host.endsWith("rutube.ru") -> "https://rutube.ru/video/$direct/"
      host.endsWith("youtube.com") || host.endsWith("youtu.be") ->
        "https://www.youtube.com/watch?v=$direct"
      direct.startsWith("/") && host.isNotBlank() -> "https://$host$direct"
      else -> null
    }
  }
}
