package org.xotabl4.ytdlpgui.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val TAG = "Storage"
private const val PREFS = "download_storage"
private const val KEY_TREE_URI = "tree_uri"
private const val KEY_TREE_LABEL = "tree_label"

/** Папка по умолчанию, если пользователь не выбрал другую. */
private const val DEFAULT_LABEL = "Download"

/** Куда публикуем готовый файл. */
data class Destination(val safTreeUri: String?, val label: String)

/** Готовый файл: путь для показа и ссылка для «Открыть». */
data class SavedFile(val title: String, val displayPath: String, val uri: String, val mimeType: String)

/**
 * Сохраняет готовый файл.
 *
 * yt-dlp пишет во временную папку, потом файл переносится в «Загрузки»
 * или в папку, которую выбрал пользователь.
 */
object DownloadStorage {
  private val _destination = MutableStateFlow(Destination(null, DEFAULT_LABEL))
  val destination: StateFlow<Destination> = _destination.asStateFlow()

  fun refresh(context: Context) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val tree = prefs.getString(KEY_TREE_URI, null)
    val label = prefs.getString(KEY_TREE_LABEL, null)
    _destination.value =
      if (tree != null) Destination(tree, label ?: "выбранная папка") else Destination(null, DEFAULT_LABEL)
  }

  fun setSafTree(context: Context, treeUri: Uri, label: String) {
    context
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_TREE_URI, treeUri.toString())
      .putString(KEY_TREE_LABEL, label)
      .apply()
    _destination.value = Destination(treeUri.toString(), label)
    Log.i(TAG, "destination = SAF '$label'")
  }

  fun resetToDefault(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    _destination.value = Destination(null, DEFAULT_LABEL)
    Log.i(TAG, "destination = default Download")
  }

  /** Временная папка для одной загрузки. */
  fun tempDir(context: Context, sub: String? = null): File =
    File(File(context.cacheDir, "downloads"), sub.orEmpty()).apply { mkdirs() }

  /** Удаляет недокачанные части. */
  fun clearTemp(context: Context, sub: String? = null): Long {
    val dir = tempDir(context, sub)
    var freed = 0L
    dir.listFiles()?.forEach { file ->
      freed += file.length()
      if (file.isDirectory) file.deleteRecursively() else file.delete()
    }
    Log.i(TAG, "temp cleared${if (sub.isNullOrBlank()) "" else " ($sub)"}: $freed bytes")
    return freed
  }

  fun mimeTypeFor(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
      "mp4" -> "video/mp4"
      "mkv" -> "video/x-matroska"
      "webm" -> "video/webm"
      "avi" -> "video/x-msvideo"
      "mov" -> "video/quicktime"
      "mp3" -> "audio/mpeg"
      "m4a" -> "audio/mp4"
      "opus" -> "audio/opus"
      "flac" -> "audio/flac"
      "wav" -> "audio/wav"
      "ogg" -> "audio/ogg"
      else -> "application/octet-stream"
    }

  /** Публикует файл в «Загрузки» или в выбранную папку. */
  suspend fun publish(context: Context, source: File, subDir: String? = null): SavedFile =
    withContext(Dispatchers.IO) {
      val name = sanitize(source.name)
      val mime = mimeTypeFor(name)
      val folder = subDir?.let { sanitizeDir(it) }?.takeIf { it.isNotBlank() }
      val destination = _destination.value
      val saved =
        when {
          destination.safTreeUri != null ->
            publishToTree(context, Uri.parse(destination.safTreeUri), source, name, mime, folder)
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            publishToMediaStore(context, source, name, mime, folder)
          else -> publishToLegacyDownloads(context, source, name, mime, folder)
        }
      Log.i(TAG, "saved: ${saved.displayPath} (${saved.uri})")
      saved
    }

  /** Имя подпапки: без запрещённых символов и точек в конце. */
  fun sanitizeDir(rawName: String): String {
    val cleaned = rawName.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trimEnd('.', ' ')
    return if (cleaned.length <= 80) cleaned else cleaned.take(80).trimEnd('.', ' ')
  }

  fun sanitize(rawName: String): String {
    val cleaned = rawName.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    val hasExt = cleaned.contains('.')
    val base = (if (hasExt) cleaned.substringBeforeLast('.') else cleaned).trimEnd('.', ' ')
    val ext = if (hasExt) cleaned.substringAfterLast('.') else ""
    val normalized =
      when {
        base.isBlank() && ext.isBlank() -> "video"
        base.isBlank() -> "video.$ext"
        ext.isBlank() -> base
        else -> "$base.$ext"
      }
    return if (normalized.length <= 120) {
      normalized
    } else {
      normalized.take(112) + "." + normalized.substringAfterLast('.', "")
    }
  }

  private fun publishToMediaStore(
    context: Context,
    source: File,
    name: String,
    mime: String,
    folder: String?,
  ): SavedFile {
    val resolver = context.contentResolver
    val values =
      ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        // Без плейлиста — прямо в «Загрузки», с плейлистом — в подпапку с его названием.
        put(
          MediaStore.Downloads.RELATIVE_PATH,
          if (folder == null) {
            Environment.DIRECTORY_DOWNLOADS
          } else {
            "${Environment.DIRECTORY_DOWNLOADS}/$folder"
          },
        )
        put(MediaStore.Downloads.IS_PENDING, 1)
      }
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: error("MediaStore: не удалось создать запись")
    resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
      ?: error("MediaStore: не удалось открыть поток")
    values.clear()
    values.put(MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    // MediaStore добавляет «(1)» к совпадающим именам, поэтому фактическое имя
    // перечитываем из записи: иначе путь в истории разойдётся с файлом.
    val actualName =
      runCatching {
          resolver
            .query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }
        .getOrNull()
        ?: name
    val path = if (folder == null) "$DEFAULT_LABEL/$actualName" else "$DEFAULT_LABEL/$folder/$actualName"
    return SavedFile(source.nameWithoutExtension, path, uri.toString(), mime)
  }

  private fun publishToLegacyDownloads(
    context: Context,
    source: File,
    name: String,
    mime: String,
    folder: String?,
  ): SavedFile {
    @Suppress("DEPRECATION")
    val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val dir = if (folder == null) base else File(base, folder)
    dir.mkdirs()
    val target = uniqueFile(dir, name)
    source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    val path = if (folder == null) "$DEFAULT_LABEL/${target.name}" else "$DEFAULT_LABEL/$folder/${target.name}"
    return SavedFile(source.nameWithoutExtension, path, uri.toString(), mime)
  }

  private fun publishToTree(
    context: Context,
    treeUri: Uri,
    source: File,
    name: String,
    mime: String,
    folder: String?,
  ): SavedFile {
    val resolver = context.contentResolver
    val parentId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
    val parentUri = if (folder == null) rootUri else childDir(context, treeUri, rootUri, folder)
    val newUri =
      DocumentsContract.createDocument(resolver, parentUri, mime, name) ?: error("SAF: не удалось создать файл")
    resolver.openOutputStream(newUri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
      ?: error("SAF: не удалось открыть поток")
    val label = _destination.value.label
    val path = if (folder == null) "$label/$name" else "$label/$folder/$name"
    return SavedFile(source.nameWithoutExtension, path, newUri.toString(), mime)
  }

  /** Находит существующую подпапку в выбранном SAF-дереве или создаёт её. */
  private fun childDir(context: Context, treeUri: Uri, parentUri: Uri, folder: String): Uri {
    val resolver = context.contentResolver
    val childrenUri =
      DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parentUri))
    resolver
      .query(
        childrenUri,
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
      )
      ?.use { cursor ->
        while (cursor.moveToNext()) {
          if (cursor.getString(1) == folder) {
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
          }
        }
      }
    return DocumentsContract.createDocument(
      resolver,
      parentUri,
      DocumentsContract.Document.MIME_TYPE_DIR,
      folder,
    ) ?: error("SAF: не удалось создать папку")
  }

  private fun uniqueFile(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "")
    var index = 1
    while (candidate.exists()) {
      val suffix = " ($index)"
      candidate = File(dir, if (ext.isEmpty()) "$base$suffix" else "$base$suffix.$ext")
      index++
    }
    return candidate
  }
}
