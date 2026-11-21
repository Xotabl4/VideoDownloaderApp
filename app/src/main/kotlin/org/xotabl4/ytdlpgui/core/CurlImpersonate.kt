package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.zip.ZipInputStream

private const val TAG = "CurlImpersonate"

/**
 * Установка `curl_cffi` в Python-дерево youtubedl-android.
 *
 * `--impersonate` в yt-dlp без этого модуля не работает. Сборка лежит в APK:
 * `jniLibs/arm64-v8a/libcurl-impersonate.so` со своим `libcxxff.so` и
 * `assets/curl_cffi.zip` (пакет, `_wrapper.cpython-312.so`, `cffi`, `certifi`).
 *
 * Распаковка идёт в `site-packages` уже развёрнутого Python: zipimport не умеет
 * загружать бинарные расширения, файлы должны лежать на диске.
 */
object CurlImpersonate {
  private const val ASSET = "curl_cffi.zip"

  @Volatile private var ready = false

  /** Модуль на месте (или успешно распакован сейчас). */
  fun ensure(context: Context): Boolean {
    if (ready) return true
    val sitePackages = sitePackages(context)
    if (sitePackages == null) {
      Log.i(TAG, "python ещё не распакован — impersonation недоступен")
      return false
    }
    // Распаковываем всегда (бандл ~3 МБ): так обновления пакета подхватываются без маркеров.
    val count =
      runCatching { extract(context, sitePackages) }
        .onFailure { Log.w(TAG, "extract failed: ${it.message}", it) }
        .getOrDefault(-1)
    Log.i(TAG, "extract: entries=$count -> ${sitePackages.absolutePath}")
    val ok = File(sitePackages, "curl_cffi").isDirectory && File(sitePackages, "certifi").isDirectory
    if (!ok) {
      Log.w(TAG, "бандл неполный (нет curl_cffi/certifi)")
      return false
    }
    copyLibcxx(sitePackages)
    ready = true
    Log.i(TAG, "curl_cffi готов: ${sitePackages.absolutePath}")
    return true
  }

  /** Распаковка бандла: каждый файл отдельно, чтобы один сбой не отменял остальные. */
  private fun extract(context: Context, sitePackages: File): Int {
    var count = 0
    context.assets.open(ASSET).use { input ->
      ZipInputStream(input).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
          if (!entry.isDirectory) {
            val name = entry.name
            if (!name.contains("..")) {
              val out = File(sitePackages, name)
              val written =
                runCatching {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                    true
                  }
                  .onFailure { Log.w(TAG, "не записал $name: ${it.message}") }
                  .getOrDefault(false)
              if (written) count++
            }
          }
          zip.closeEntry()
          entry = zip.nextEntry
        }
      }
    }
    return count
  }

  /** Включает impersonation. */
  private const val ENABLED = true

  /** Диагностика: пишет список impersonate-целей в imp_probe.txt. */
  suspend fun probe(context: Context) {
    val site = sitePackages(context) ?: return
    // Принудительно распаковываем бандл (маркер игнорируем): нужен свежий sitecustomize.
    runCatching {
      context.assets.open(ASSET).use { input ->
        ZipInputStream(input).use { zip ->
          var entry = zip.nextEntry
          while (entry != null) {
            if (!entry.isDirectory) {
              val out = File(site, entry.name)
              out.parentFile?.mkdirs()
              out.outputStream().use { zip.copyTo(it) }
            }
            zip.closeEntry()
            entry = zip.nextEntry
          }
        }
      }
    }
    copyLibcxx(site)
    val request = YoutubeDLRequest("https://example.com/").apply {
      addOption("--list-impersonate-targets")
    }
    val text =
      runCatching { YoutubeDL.getInstance().execute(request) }
        .fold(
          onSuccess = { r -> "exit=${r.exitCode}\n--- out ---\n${r.out}\n--- err ---\n${r.err}" },
          onFailure = { e -> "exception: ${e.message}\n${e.stackTraceToString().take(2000)}" },
        )
    runCatching {
      val dir = context.getExternalFilesDir(null) ?: context.filesDir
      File(dir, "imp_probe.txt").writeText(text)
    }
    Log.i(TAG, "probe: ${text.take(300)}")
  }

  /** Копирует libcxxff.so в usr/lib, откуда его ищет libcurl. */
  private fun copyLibcxx(sitePackages: File) {
    runCatching {
      val usrLib = sitePackages.parentFile?.parentFile ?: return
      val source = File(sitePackages, "libcxxff.so")
      if (source.isFile) source.copyTo(File(usrLib, "libcxxff.so"), overwrite = true)
    }
  }

  /** Нужен ли impersonation для этой ссылки. */
  fun wantedFor(url: String): Boolean {
    if (!ENABLED) return false
    val host = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull() ?: return false
    return host.endsWith("youtube.com") ||
      host.endsWith("youtu.be") ||
      host.endsWith("pornhub.com") ||
      host.endsWith("phncdn.com")
  }

  /** `site-packages` распакованного Python (ищем `python3.*`, без хардкода версии). */
  private fun sitePackages(context: Context): File? {
    val base = File(context.noBackupFilesDir, "youtubedl-android")
    val libs = File(base, "packages/python/usr/lib")
    val dirs = libs.listFiles() ?: return null
    val pythonDir = dirs.firstOrNull { it.isDirectory && it.name.startsWith("python3.") } ?: return null
    return File(pythonDir, "site-packages").apply { mkdirs() }
  }
}
