package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xotabl4.ytdlpgui.R

private const val TAG = "Formats"

/** `https://rutube.ru/plst/443570/` → `443570`. */
private val RUTUBE_PLAYLIST_REGEX = Regex("""rutube\.ru/plst/(\d+)""")

/**
 * Замена `downloader.get_formats(url)`:
 * десктоп запускал `bin\yt-dlp.exe -J <url>` через `subprocess`,
 * здесь то же самое делает `YoutubeDL.execute(request)`.
 */
object FormatsRepository {
  suspend fun fetch(context: Context, url: String): Formats =
    withContext(Dispatchers.IO) {
      // Сайты с DRM (Кинопоиск, Okko, Netflix…) даже не пробуем — сразу объясняем причину.
      DownloadErrors.unsupportedSite(context, url)?.let { throw IllegalStateException(it) }
      // PornHub требует impersonation (curl_cffi), которого нет в бандле: качества и прямые
      // ссылки забираем из скрытого WebView и отдаём их пользователю списком.
      if (PhCookies.isPornHub(url)) {
        val result = PhHarvest.resolveAll(context, url)
        if (result.variants.isEmpty()) {
          throw IllegalStateException(context.getString(R.string.error_pornhub_stream))
        }
        return@withContext Formats(
          title = result.title.ifBlank { "PornHub" },
          videoAudio =
            result.variants.map { variant ->
              FormatItem(
                formatId = "ph:${variant.quality}",
                shortDesc = "${variant.quality}p",
                tooltip = "${variant.format} · ${variant.quality}p",
                ext = variant.format,
                width = 0,
                height = variant.quality,
                tbr = 0.0,
                hasVideo = true,
                hasAudio = true,
                downloadId = "ph:${variant.quality}",
                rawFilesize = 0,
                vcodecShort = "avc1",
                acodecShort = "mp4a",
              )
            },
          videoOnly = emptyList(),
          audioOnly = emptyList(),
          thumbnail = "",
          durationSeconds = 0L,
        )
      }

      // Диагностика impersonation доступна отдельно (CurlImpersonate.probe), в рабочем пути не нужна.

      val effectiveUrl = url
      // Для плейлиста хватает первых трёх роликов: форматы у них одинаковые, а `-J` целиком
      // на большом плейлисте считается десятки секунд.
      val request =
        YoutubeDLRequest(effectiveUrl).apply {
          addOption("-J")
          // Impersonation: подменяем TLS/HTTP2-отпечаток на Chrome — снимает часть антибот-блоков.
          if (CurlImpersonate.wantedFor(url) && CurlImpersonate.ensure(context)) {
            addOption("--impersonate", "chrome")
          }
          // PornHub без подтверждения возраста отдаёт 403: передаём Netscape-cookies и UA браузера.
          if (PhCookies.isPornHub(url)) {
            addOption("--cookies", PhCookies.file(context).absolutePath)
            addOption("--user-agent", PhCookies.USER_AGENT)
            addOption("--referer", "https://www.pornhub.com/")
            addOption("--add-header", "Origin: https://www.pornhub.com")
          }
          // `--flat-playlist` не разворачивает каждый ролик: приватные/удалённые больше не
          // валят код возврата (а вместе с ним — и весь разбор плейлиста).
          addOption("--flat-playlist")
          addOption("--playlist-end", "5")
          addOption("--ignore-errors")
        }
      val started = System.currentTimeMillis()
      // Библиотека бросает исключение на любом ненулевом коде возврата — переводим текст сразу,
      // иначе на экран попадает `[generic] video: Unable to download webpage: HTTP Error 404`.
      val response =
        try {
          YoutubeDL.getInstance().execute(request)
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          saveLastError(context, url, e.message.orEmpty(), response = null)
          throw IllegalStateException(
            vkBlockedMessage(context, url)
              ?: DownloadErrors.forUrl(context, url, e.message.orEmpty()),
          )
        }
      val elapsed = System.currentTimeMillis() - started
      Log.i(TAG, "yt-dlp -J: exit=${response.exitCode}, ${elapsed} ms, out=${response.out.length} chars")

      // Код возврата может быть ненулевым из-за приватных/удалённых роликов в плейлисте,
      // но JSON всё равно приходит — поэтому смотрим на содержимое, а не только на exitCode.
      val parsed =
        try {
          FormatsJson.parse(response.out)
        } catch (e: Exception) {
          val raw = response.err.ifBlank { response.out }.ifBlank { e.message.orEmpty() }
          Log.w(
            TAG,
            "parse failed: exit=${response.exitCode}, out=${response.out.length} chars, " +
              "err=${response.err.take(400)}",
            e,
          )
          saveLastError(context, url, raw, response)
          throw IllegalStateException(vkBlockedMessage(context, url) ?: DownloadErrors.forUrl(context, url, raw))
        }
      // RuTube не кладёт название плейлиста в `-J` — добираем его из публичного API.
      val rutube = if (parsed.isPlaylist && parsed.playlistTitle.isBlank()) rutubePlaylistMeta(url) else null
      var playlistTitle = rutube?.first ?: parsed.playlistTitle
      var playlistCount = rutube?.second ?: parsed.playlistCount
      // VK не отдаёт в `-J` ни названия, ни количества видео (страница собирается скриптами):
      // берём их из того же `al_video.php`, которым пользуется сам экстрактор.
      if (parsed.isPlaylist && playlistTitle.isBlank() && VkPlaylist.matches(url)) {
        when (val vk = VkPlaylist.fetch(url)) {
          is VkPlaylist.Info.Ok -> {
            playlistTitle = vkFolder(context, vk)
            // `-J` для VK отдаёт `playlist_count=null`, поэтому там число = длине
            // запрошенной выборки (`--playlist-end 5`); реальное берём из `al_video.php`.
            if (vk.total > 0) playlistCount = vk.total
          }
          VkPlaylist.Info.Blocked ->
            throw IllegalStateException(context.getString(R.string.error_vk_blocked))
          VkPlaylist.Info.LoginRequired ->
            throw IllegalStateException(context.getString(R.string.error_vk_login))
          VkPlaylist.Info.Unknown -> Unit
        }
      }

      // `--flat-playlist` не отдаёт форматы — запрашиваем их у первого ролика, у которого
      // они есть: в плейлисте бывают удалённые/заблокированные записи, и первый же
      // ролик может оказаться недоступным.
      var rawFormats = parsed.formats
      var thumbnail = parsed.thumbnail
      var durationSeconds = parsed.durationSeconds
      if (parsed.isPlaylist && rawFormats.isEmpty()) {
        var lastError: Exception? = null
        for (entryUrl in parsed.entryUrls) {
          try {
            val single = fetchSingle(context, entryUrl)
            if (single.formats.isNotEmpty()) {
              rawFormats = single.formats
              thumbnail = single.thumbnail
              durationSeconds = single.durationSeconds
              break
            }
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Exception) {
            lastError = e
            Log.w(TAG, "playlist entry failed: ${entryUrl.take(100)}: ${e.message?.take(120)}")
          }
        }
        if (rawFormats.isEmpty()) {
          throw lastError ?: IllegalStateException(context.getString(R.string.error_playlist_empty))
        }
      }

      val formats =
        FormatsParser.build(
          title = if (parsed.isPlaylist && playlistTitle.isNotBlank()) playlistTitle else parsed.title,
          raw = rawFormats,
          thumbnail = thumbnail,
          durationSeconds = durationSeconds,
          playlistTitle = playlistTitle,
          playlistCount = playlistCount,
          isPlaylist = parsed.isPlaylist,
        )
      Log.i(
        TAG,
        "parsed '${formats.title}': videoAudio=${formats.videoAudio.size}, " +
          "videoOnly=${formats.videoOnly.size}, audioOnly=${formats.audioOnly.size}",
      )
      formats
    }

  /**
   * Имя подпапки для плейлиста VK: площадка не отдаёт название подборки, поэтому
   * собираем его из владельца и номера плейлиста — «ФАН ТВ — VK плейлист 2».
   */
  private fun vkFolder(context: Context, vk: VkPlaylist.Info.Ok): String =
    if (vk.owner.isBlank()) {
      context.getString(R.string.vk_playlist_folder, vk.sectionId)
    } else {
      context.getString(R.string.vk_playlist_folder_owner, vk.owner, vk.sectionId)
    }

  /**
   * VK на ограниченные страницы отвечает `blank.php?rkn=<id>`, и yt-dlp падает
   * с `TypeError: string indices must be integers`. Проверяем причину отдельно,
   * чтобы вместо технической строки показать понятную фразу.
   */
  private fun vkBlockedMessage(context: Context, url: String): String? {
    if (!VkPlaylist.matches(url)) return null
    return when (VkPlaylist.fetch(url)) {
      is VkPlaylist.Info.Blocked -> context.getString(R.string.error_vk_blocked)
      VkPlaylist.Info.LoginRequired -> context.getString(R.string.error_vk_login)
      else -> null
    }
  }

  /**
   * Диагностика: последняя ошибка пишется в `Android/data/<pkg>/files/last_error.txt`,
   * чтобы её можно было посмотреть через adb (в release logcat приложения закрыт).
   */
  private fun saveLastError(
    context: Context,
    url: String,
    raw: String,
    response: com.yausername.youtubedl_android.YoutubeDLResponse?,
  ) {
    runCatching {
      val dir = context.getExternalFilesDir(null) ?: context.filesDir
      val body =
        buildString {
          appendLine("url: $url")
          appendLine("exit: ${response?.exitCode ?: "-"}")
          appendLine("--- stderr ---")
          appendLine(raw.take(4000))
          if (response != null && response.out.isNotBlank()) {
            appendLine("--- stdout (head) ---")
            appendLine(response.out.take(1000))
          }
        }
      java.io.File(dir, "last_error.txt").writeText(body)
    }
  }

  /** Форматы одного ролика (`-J` по его URL) — для плейлиста, разобранного через `--flat-playlist`. */
  private fun fetchSingle(context: Context, url: String): FormatsJson.Parsed {
    val request =
      YoutubeDLRequest(url).apply {
        addOption("-J")
        if (CurlImpersonate.wantedFor(url) && CurlImpersonate.ensure(context)) {
          addOption("--impersonate", "chrome")
        }
        if (PhCookies.isPornHub(url)) {
          addOption("--cookies", PhCookies.file(context).absolutePath)
          addOption("--user-agent", PhCookies.USER_AGENT)
        }
      }
    val response =
      try {
        YoutubeDL.getInstance().execute(request)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        throw IllegalStateException(DownloadErrors.forUrl(context, url, e.message.orEmpty()))
      }
    return try {
      FormatsJson.parse(response.out)
    } catch (e: Exception) {
      val raw = response.err.ifBlank { response.out }.ifBlank { e.message.orEmpty() }
      throw IllegalStateException(DownloadErrors.forUrl(context, url, raw))
    }
  }

  /**
   * Название и число видео плейлиста RuTube: yt-dlp для него не заполняет `title`,
   * а публичный API `api/playlist/custom/<id>/` отдаёт и `title`, и `videos_count`.
   */
  private fun rutubePlaylistMeta(url: String): Pair<String, Int>? {
    val id = RUTUBE_PLAYLIST_REGEX.find(url)?.groupValues?.getOrNull(1) ?: return null
    return runCatching {
        val connection =
          (URL("https://rutube.ru/api/playlist/custom/$id/").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
          }
        try {
          if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
          val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
          val json = JSONObject(body)
          val title = json.optString("title").trim()
          if (title.isEmpty()) return null
          title to json.optInt("videos_count", 0)
        } finally {
          connection.disconnect()
        }
      }
      .getOrNull()
  }

}
