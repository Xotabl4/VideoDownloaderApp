package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "PhCookies"

/** Файл cookies, который снимает возрастную проверку PornHub. */
object PhCookies {
  private const val FILE_NAME = "cookies-pornhub.txt"

  /** Браузерный User-Agent. */
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

  /** Ссылка ведёт на PornHub. */
  fun isPornHub(url: String): Boolean = url.contains("pornhub.com", ignoreCase = true)
}
