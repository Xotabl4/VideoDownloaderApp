package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.zip.ZipInputStream

private const val TAG = "CurlImpersonate"

/**
 * Установка `curl_cffi` (impersonation TLS/HTTP2) в Python-дерево youtubedl-android.
 *
 * Зачем: yt-dlp для `--impersonate` требует модуль `curl_cffi`, которого нет в zipapp.
 * Собрано отдельно (BoringSSL + curl-impersonate под arm64) и положено в APK:
 * - `jniLibs/arm64-v8a/libcurl-impersonate.so`, `libcxxff.so` (свой libc++, чтобы не
 *   столкнуться со старой `libc++_shared.so` из youtubedl-android — тот же приём, что в [ElfPatch]);
 * - `assets/curl_cffi.zip` — python-пакет + `_wrapper.cpython-312.so` (ABI совпадает с
 *   Python 3.12 из библиотеки: `SOABI=cpython-312`).
 *
 * Пакет распаковывается в `site-packages` уже распакованного Python: zipimport не умеет
 * грузить бинарные расширения, поэтому `curl_cffi` должен лежать файлами на диске.
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

  /**
   * Аварийный выключатель: пока `import curl_cffi` в бандл-Python не подтверждён на устройстве,
   * `--impersonate` нельзя передавать — yt-dlp падает с `target "chrome" is not available`
   * и ломает обычные ссылки (проверено 14.11.2026). Включать после отладки импорта.
   */
  private const val ENABLED = true

  /**
   * Диагностика (временно): спрашиваем у yt-dlp список доступных impersonate-целей и пишем
   * stdout/stderr в `Android/data/<pkg>/files/imp_probe.txt`. Команда не падает (exit 0),
   * поэтому библиотека отдаёт полный ответ, включая текст `ImportError` для curl_cffi.
   */
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

  /**
   * `libcurl-impersonate.so` собран без RUNPATH, а ему нужен `libcxxff.so`. Каталог
   * `usr/lib` библиотека youtubedl-android кладёт в `LD_LIBRARY_PATH` для Python,
   * поэтому копируем libc++ туда.
   */
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
