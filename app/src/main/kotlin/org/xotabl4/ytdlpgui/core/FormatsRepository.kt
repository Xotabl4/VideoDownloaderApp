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

/** Форматы по ссылке: `yt-dlp -J`, разбор JSON и категоризация. */
object FormatsRepository {
  suspend fun fetch(context: Context, url: String): Formats =
    withContext(Dispatchers.IO) {
      // Сайты с DRM не пробуем, сразу объясняем причину.
      DownloadErrors.unsupportedSite(context, url)?.let { throw IllegalStateException(it) }
      // PornHub: с curl_cffi (`--impersonate chrome`) yt-dlp сам проходит антибот-проверку
      // и отдаёт список качеств. Если impersonation недоступен — запасной путь через WebView.
      if (PhCookies.isPornHub(url)) {
        val direct =
          runCatching { fetchSingle(context, url) }
            .onFailure { Log.w(TAG, "pornhub direct failed: ${it.message?.take(200)}") }
            .getOrNull()
        if (direct != null && direct.formats.isNotEmpty()) {
          val formats =
            FormatsParser.build(
              title = direct.title.ifBlank { "PornHub" },
              raw = direct.formats,
              thumbnail = direct.thumbnail,
              durationSeconds = direct.durationSeconds,
              isPlaylist = false,
            )
          Log.i(TAG, "pornhub via impersonate: formats=${direct.formats.size}")
          return@withContext formats
        }
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

      // Канал VK: yt-dlp не разбирает страницу `@name` («Unable to extract cursor data»),
      // поэтому список видео и название берём сами, а форматы — у первого ролика.
      if (VkChannel.matches(url)) {
        when (val vk = VkChannel.resolve(context, url, limit = 20)) {
          is VkChannel.Result.Ok -> {
            val info = vk.info
            val first =
              info.entries.firstOrNull()
                ?: throw IllegalStateException(context.getString(R.string.error_vk_playlist))
            val single = fetchSingle(context, first)
            val formats =
              FormatsParser.build(
                title = info.title,
                raw = single.formats,
                thumbnail = single.thumbnail,
                durationSeconds = single.durationSeconds,
                playlistTitle = info.title,
                playlistCount = info.total,
                isPlaylist = true,
              )
            Log.i(
              TAG,
              "vk channel '${info.title}': total=${info.total}, entries=${info.entries.size}, " +
                "formats=${single.formats.size}",
            )
            return@withContext formats
          }
          VkChannel.Result.Blocked ->
            throw IllegalStateException(context.getString(R.string.error_vk_blocked))
          VkChannel.Result.LoginRequired ->
            throw IllegalStateException(context.getString(R.string.error_vk_channel))
          VkChannel.Result.Unknown -> Unit
        }
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

      // У плейлиста форматы берём у первого доступного ролика.
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

  /** Имя папки для плейлиста VK: владелец и номер. */
  private fun vkFolder(context: Context, vk: VkPlaylist.Info.Ok): String =
    if (vk.owner.isBlank()) {
      context.getString(R.string.vk_playlist_folder, vk.sectionId)
    } else {
      context.getString(R.string.vk_playlist_folder_owner, vk.owner, vk.sectionId)
    }

  /** Понятный текст, если VK ограничил доступ к плейлисту. */
  private fun vkBlockedMessage(context: Context, url: String): String? {
    if (!VkPlaylist.matches(url)) return null
    return when (VkPlaylist.fetch(url)) {
      is VkPlaylist.Info.Blocked -> context.getString(R.string.error_vk_blocked)
      VkPlaylist.Info.LoginRequired -> context.getString(R.string.error_vk_login)
      else -> null
    }
  }

  /** Пишет последнюю ошибку в файл, чтобы посмотреть её через adb. */
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
          addOption("--referer", "https://www.pornhub.com/")
          addOption("--add-header", "Origin: https://www.pornhub.com")
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
