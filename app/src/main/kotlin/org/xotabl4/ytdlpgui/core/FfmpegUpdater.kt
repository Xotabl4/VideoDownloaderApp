package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import org.tukaani.xz.XZInputStream
import org.xotabl4.ytdlpgui.R

private const val TAG = "FfmpegUpdater"

/** Точка монтирования байтов для колбэка прогресса: текст + процент (может быть null). */
typealias UpdateProgress = (message: String, percent: Int?) -> Unit

/**
 * Обновление ffmpeg из репозитория Termux — того же, из которого `apt install ffmpeg` в Termux
 * берёт актуальную сборку (на 08.11.2026 это 9.0.2, в APK вшита та же).
 *
 * Формат Debian-пакета разбираем сами (`ar` + `data.tar.xz` + `tar`): нужен только xz-декодер
 * (`org.tukaani:xz`), остальное — десятки строк кода, без тяжёлых зависимостей.
 */
object FfmpegUpdater {
  private const val BASE = "https://packages.termux.dev/apt/termux-main"
  private const val ARCH = "aarch64"
  private const val TIMEOUT_MS = 30_000

  /** Последняя версия ffmpeg в репозитории Termux (`9.0.2` из `9.0.2-1`). */
  fun latestVersion(): String? =
    runCatching {
        val index = download("$BASE/dists/stable/main/binary-$ARCH/Packages")
        parsePackages(index)["ffmpeg"]?.get("Version")?.substringBefore('-')
      }
      .onFailure { Log.w(TAG, "latestVersion failed", it) }
      .getOrNull()

  /** Сравнение версий «9.0.2» / "7.1.1": >0 если [left] новее. */
  fun compare(left: String, right: String): Int {
    val a = left.split('.').mapNotNull { it.toIntOrNull() }
    val b = right.split('.').mapNotNull { it.toIntOrNull() }
    for (index in 0 until maxOf(a.size, b.size)) {
      val diff = (a.getOrNull(index) ?: 0) - (b.getOrNull(index) ?: 0)
      if (diff != 0) return diff
    }
    return 0
  }

  /**
   * Скачивает и ставит ffmpeg версии [version]: пакет `ffmpeg` + замыкание зависимостей,
   * из каждого пакета берём только `usr/bin/ffmpeg|ffprobe` и `usr/lib/ (so-файлы)`.
   */
  fun install(context: Context, version: String, onProgress: UpdateProgress): String? {
    onProgress(context.getString(R.string.ffmpeg_progress_index), null)
    val packages = parsePackages(download("$BASE/dists/stable/main/binary-$ARCH/Packages"))
    val closure = dependencyClosure(packages, "ffmpeg")
    Log.i(TAG, "ffmpeg $version: пакетов в замыкании ${closure.size}")

    val work = File(context.filesDir, "ffmpeg-download").apply { mkdirs() }
    work.deleteRecursively()
    val realDir = File(work, "real").apply { mkdirs() }
    val libDir = File(work, "lib").apply { mkdirs() }
    val debDir = File(work, "deb").apply { mkdirs() }
    val symlinks = mutableListOf<Pair<String, String>>()

    var done = 0
    for (name in closure) {
      val info = packages[name] ?: continue
      val filename = info["Filename"] ?: continue
      val deb = File(debDir, filename.substringAfterLast('/'))
      downloadTo("$BASE/$filename", deb) { percent ->
        onProgress(context.getString(R.string.ffmpeg_progress_download, name, done, closure.size), percent)
      }
      extractDeb(deb, realDir, libDir, symlinks)
      deb.delete()
      done++
      onProgress(context.getString(R.string.ffmpeg_progress_unpack, done, closure.size), null)
    }

    check(File(realDir, "ffmpeg").exists()) { "в загрузке нет ffmpeg" }
    return FfmpegRuntime.installDownloaded(context, version, realDir, libDir, symlinks).also {
      File(work.absolutePath).deleteRecursively()
      onProgress(context.getString(R.string.ffmpeg_progress_check), null)
    }
  }

  private fun parsePackages(text: String): Map<String, Map<String, String>> {
    val result = mutableMapOf<String, Map<String, String>>()
    text.split("\n\n").forEach { block ->
      val info = mutableMapOf<String, String>()
      block.lineSequence().forEach { line ->
        if (line.isNotBlank() && !line.startsWith(" ")) {
          val colon = line.indexOf(':')
          if (colon > 0) info[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
      }
      info["Package"]?.let { result[it] = info }
    }
    return result
  }

  private fun dependencyClosure(
    packages: Map<String, Map<String, String>>,
    root: String,
  ): List<String> {
    val seen = mutableListOf<String>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
      val name = queue.removeFirst()
      if (name in seen) continue
      if (name !in packages) continue
      seen += name
      packages[name]?.get("Depends").orEmpty().split(',').forEach { dep ->
        dep.split('|').firstOrNull()
          ?.trim()
          ?.substringBefore(' ')
          ?.substringBefore('(')
          ?.takeIf { it.isNotBlank() }
          ?.let { queue.addLast(it) }
      }
    }
    return seen
  }

  private fun download(url: String): String = downloadBytes(url).toString(Charsets.UTF_8)

  private fun downloadBytes(url: String): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = TIMEOUT_MS
    connection.readTimeout = TIMEOUT_MS
    connection.instanceFollowRedirects = true
    return connection.inputStream.use { it.readBytes() }
  }

  private fun downloadTo(url: String, target: File, onPercent: (Int?) -> Unit) {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = TIMEOUT_MS
    connection.readTimeout = TIMEOUT_MS
    connection.instanceFollowRedirects = true
    val total = connection.contentLength.toLong()
    connection.inputStream.use { input ->
      target.outputStream().use { output ->
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        var lastPercent = -1
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          output.write(buffer, 0, count)
          read += count
          if (total > 0) {
            val percent = (read * 100 / total).toInt()
            if (percent != lastPercent) {
              lastPercent = percent
              onPercent(percent)
            }
          }

        }
      }
    }
  }

  /** Достаёт из .deb (`ar`-архив) `data.tar.xz` и раскладывает нужные файлы. */
  private fun extractDeb(
    deb: File,
    realDir: File,
    libDir: File,
    symlinks: MutableList<Pair<String, String>>,
  ) {
    RandomAccessFile(deb, "r").use { file ->
      val magic = ByteArray(8)
      file.readFully(magic)
      check(String(magic, Charsets.US_ASCII) == "!<arch>\n") { "не ar-архив: ${deb.name}" }
      val header = ByteArray(60)
      while (true) {
        if (file.read(header) < 60) break
        val name = String(header, 0, 16, Charsets.US_ASCII).trim().trimEnd('/')
        val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLongOrNull() ?: 0L
        val start = file.filePointer
        if (name.startsWith("data.tar")) {
          val payload = ByteArray(size.toInt())
          file.readFully(payload)
          val stream: InputStream =
            if (name.endsWith(".xz")) XZInputStream(payload.inputStream())
            else payload.inputStream()
          stream.use { readTar(it, realDir, libDir, symlinks) }
        }
        file.seek(start + size + (size % 2))
      }
    }
  }

  private fun readTar(
    input: InputStream,
    realDir: File,
    libDir: File,
    symlinks: MutableList<Pair<String, String>>,
  ) {
    val header = ByteArray(512)
    while (readFully(input, header)) {
      if (header.all { it == 0.toByte() }) break
      var path = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000').trim()
      path = path.removePrefix("./").removePrefix("data/data/com.termux/files/")
      val size = String(header, 124, 12, Charsets.US_ASCII).trim().trimEnd('\u0000').trim()
        .toLongOrNull(8) ?: 0L
      val type = header[156].toInt().toChar()
      val linkName = String(header, 157, 100, Charsets.UTF_8).trimEnd('\u0000').trim()

      val baseName = path.substringAfterLast('/')
      val target: File? =
        when {
          path.startsWith("usr/bin/") && baseName in setOf("ffmpeg", "ffprobe") && type == '0' ->
            File(realDir, baseName)
          path.startsWith("usr/lib/") && type == '0' && baseName.contains(".so") -> File(libDir, baseName)
          path.startsWith("usr/lib/") && type == '2' && baseName.isNotBlank() -> {
            symlinks += baseName to linkName.substringAfterLast('/')
            null
          }
          else -> null
        }

      var remaining = size
      if (target != null) {
        target.parentFile?.mkdirs()
        target.outputStream().use { out ->
          val buffer = ByteArray(64 * 1024)
          while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            out.write(buffer, 0, count)
            remaining -= count
          }
        }
        if (baseName == "ffmpeg" || baseName == "ffprobe") target.setExecutable(true, false)
      } else {
        remaining = skip(input, remaining)
      }
      val padding = (512 - (size % 512)) % 512
      if (padding > 0) skip(input, padding)
    }
  }

  private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
      val read = input.read(buffer, offset, buffer.size - offset)
      if (read < 0) return false
      offset += read
    }
    return true
  }

  private fun skip(input: InputStream, count: Long): Long {
    var remaining = count
    val buffer = ByteArray(64 * 1024)
    while (remaining > 0) {
      val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
      if (read < 0) break
      remaining -= read
    }
    return remaining
  }
}
