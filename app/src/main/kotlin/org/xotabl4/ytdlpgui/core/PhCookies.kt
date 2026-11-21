package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "PhCookies"

/**
 * PornHub без подтверждения возраста отдаёт `HTTP 403` (age-gate 18+), а опция
 * `--add-header "Cookie: …"` yt-dlp не доносит до сервера — нужен именно Netscape-файл cookies.
 *
 * Здесь создаём такой файл один раз в `cacheDir` и передаём его через `--cookies`.
 * Набор cookie минимальный: сам факт подтверждения возраста и «полная» версия сайта.
 */
object PhCookies {
  private const val FILE_NAME = "cookies-pornhub.txt"

  /** Браузерный User-Agent: PornHub отдаёт 403 «ботским» клиентам. */
  const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
      "Chrome/126.0.0.0 Safari/537.36"

  fun file(context: Context): File {
    val file = File(context.cacheDir, FILE_NAME)
    if (file.exists() && file.length() > 0) return file
    file.writeText(buildString {
      appendLine("# Netscape HTTP Cookie File")
      appendLine("# Created by Video Downloader for PornHub age-gate")
      appendLine(".pornhub.com\tTRUE\t/\tTRUE\t0\tage_verified\t1")
      appendLine(".pornhub.com\tTRUE\t/\tTRUE\t0\taccessAgeDisclaimerPH\t1")
      appendLine(".pornhub.com\tTRUE\t/\tTRUE\t0\tplatform\tpc")
    })
    Log.i(TAG, "cookies file ready: ${file.absolutePath} (${file.length()} bytes)")
    return file
  }

  /** Ссылка ведёт на PornHub (включая `rt.` и другие поддомены). */
  fun isPornHub(url: String): Boolean = url.contains("pornhub.com", ignoreCase = true)
}
