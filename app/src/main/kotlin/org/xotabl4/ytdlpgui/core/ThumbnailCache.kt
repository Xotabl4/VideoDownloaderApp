package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "Thumbs"

/**
 * Кэш миниатюр для карточек библиотеки.
 *
 * Библиотеку изображений (Coil/Glide) не подключаем — новых зависимостей не добавляем:
 * качаем превью обычным [HttpURLConnection] в `cacheDir/thumbs/<hash>.img` и декодируем
 * через [BitmapFactory]. Один и тот же кадр повторно из сети не тянется.
 */
object ThumbnailCache {
  /** Декодированные миниатюры в памяти: при свайпе карточки пересоздаются, декод — уже нет. */
  private val memory =
    object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
      override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

  /** Готовая миниатюра из памяти (для первого кадра без «мигания»). */
  fun peek(url: String): Bitmap? = if (url.isBlank()) null else memory.get(url)

  /** Уже скачанный файл миниатюры или `null`, если для записи её нет. */
  fun cached(context: Context, url: String): File? {
    if (url.isBlank()) return null
    val file = fileFor(context, url)
    return if (file.length() > 0L) file else null
  }

  /** Скачивает миниатюру при необходимости и возвращает декодированный [Bitmap]. */
  suspend fun load(context: Context, url: String): Bitmap? =
    withContext(Dispatchers.IO) {
      if (url.isBlank()) return@withContext null
      memory.get(url)?.let { return@withContext it }
      val file = cached(context, url) ?: download(context, url) ?: return@withContext null
      decodeScaled(file)?.also { memory.put(url, it) }
    }

  /** Декод с уменьшением: карточка ~96 dp, полноразмерную миниатюру (1280 px) хранить незачем. */
  private fun decodeScaled(file: File): Bitmap? {
    val bounds =
      BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, this)
      }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 320 && bounds.outHeight / (sample * 2) >= 180) {
      sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
  }

  private fun fileFor(context: Context, url: String): File {
    val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    return File(dir, "${url.hashCode().toUInt().toString(16)}_${url.length}.img")
  }

  private fun download(context: Context, url: String): File? =
    runCatching {
        val connection =
          (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) ytdlpgui")
          }
        connection.inputStream.use { input ->
          val target = fileFor(context, url)
          target.outputStream().use { output -> input.copyTo(output) }
          target.takeIf { it.length() > 0L }
        }
      }
      .onFailure { Log.w(TAG, "не удалось скачать превью $url: ${it.message}") }
      .getOrNull()
}
