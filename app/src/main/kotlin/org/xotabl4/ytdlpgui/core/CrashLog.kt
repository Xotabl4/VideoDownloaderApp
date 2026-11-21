package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.xotabl4.ytdlpgui.R

private const val TAG = "CrashLog"
private const val DIR_NAME = "crash"
private const val FILE_PREFIX = "crash-"
private const val FILE_SUFFIX = ".txt"

/**
 * Лог фатальных падений в файл: без adb и без сторонних библиотек (crashlytics и т.п.).
 *
 * R8 однажды уже ломал запуск (commons-compress), а увидеть ошибку можно было только по логу
 * logcat. Теперь [install] перехватывает необработанное исключение, пишет отчёт
 * в `filesDir/crash/crash-<дата>.txt` и передаёт управление прежнему обработчику,
 * поэтому система по-прежнему показывает диалог «Приложение остановлено».
 *
 * Форматирование отчёта, имя файла и правило ротации — чистые функции (`formatReport`,
 * [fileName], [filesToDelete]), покрыты юнит-тестами.
 */
object CrashLog {
  /** Сколько последних отчётов держим: больше не нужно, а дисковое место не бесконечно. */
  const val LIMIT = 5

  @Volatile private var installed = false

  private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
  private val displayFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

  /** Ставит обработчик один раз за процесс. Повторные вызовы (Application, тесты) безопасны. */
  fun install(context: Context) {
    if (installed) return
    installed = true
    val app = context.applicationContext
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      write(app, thread, error)
      // Прежний обработчик (ActivityThread) завершает процесс и показывает системный диалог.
      previous?.uncaughtException(thread, error)
    }
  }

  fun crashDir(context: Context): File = File(context.filesDir, DIR_NAME)

  /** Самый свежий отчёт или `null`, если падений ещё не было. */
  fun lastCrash(context: Context): File? =
    crashDir(context)
      .listFiles()
      ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
      ?.maxByOrNull { it.lastModified() }

  /** «08.11 21:35» — для строки в настройках. */
  fun formatTime(millis: Long): String = displayFormat.format(Date(millis))

  /** Имя отчёта: `crash-2026-11-09_12-30-15.txt` (UTC-независимо, сортируется по алфавиту). */
  fun fileName(timestamp: Long): String = synchronized(fileNameFormat) {
    "$FILE_PREFIX${fileNameFormat.format(Date(timestamp))}$FILE_SUFFIX"
  }

  /**
   * Текст отчёта. [device] и [appVersion] передаются снаружи, чтобы функция оставалась
   * пригодной для JVM-тестов без Android.
   */
  fun formatReport(
    timestamp: String,
    appVersion: String,
    device: String,
    threadName: String,
    error: Throwable,
  ): String =
    buildString {
      appendLine("Time: $timestamp")
      appendLine("App: $appVersion")
      appendLine("Device: $device")
      appendLine("Thread: $threadName")
      appendLine()
      append(error.stackTraceToString())
    }

  /**
   * Файлы к удалению. [existingNewestFirst] — отчёты, отсортированные от нового к старому;
   * всё, что не влезает в [limit], подлежит удалению.
   */
  fun filesToDelete(existingNewestFirst: List<String>, limit: Int = LIMIT): List<String> =
    if (existingNewestFirst.size <= limit) emptyList() else existingNewestFirst.drop(limit)

  /** `ACTION_SEND` с вложением-отчётом через `FileProvider` (`${applicationId}.files`). */
  fun shareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send =
      Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_share_subject, file.name))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    return Intent.createChooser(send, context.getString(R.string.crash_share_chooser))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  private fun write(context: Context, thread: Thread, error: Throwable) {
    runCatching {
        Log.e(TAG, "необработанное исключение в потоке ${thread.name}", error)
        val dir = crashDir(context)
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val file = File(dir, fileName(now))
        file.writeText(
          formatReport(
            timestamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(now)),
            appVersion = appVersion(context),
            device = device(),
            threadName = thread.name,
            error = error,
          )
        )
        prune(dir)
        Log.i(TAG, "отчёт сохранён: ${file.absolutePath}")
      }
      .onFailure { Log.w(TAG, "не удалось сохранить отчёт: ${it.message}") }
  }

  /** Оставляет [LIMIT] самых свежих отчётов: при частых падениях папка не растёт бесконечно. */
  private fun prune(dir: File) {
    val reports =
      dir
        .listFiles()
        ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
        ?.sortedByDescending { it.lastModified() }
        ?.map { it.name }
        .orEmpty()
    filesToDelete(reports).forEach { name -> runCatching { File(dir, name).delete() } }
  }

  private fun appVersion(context: Context): String =
    runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
      }
      .getOrDefault("—")

  private fun device(): String =
    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
      "${Build.MANUFACTURER} ${Build.MODEL}"
}
