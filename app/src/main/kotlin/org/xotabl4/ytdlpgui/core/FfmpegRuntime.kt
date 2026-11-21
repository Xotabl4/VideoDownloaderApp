package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.InputStream

private const val TAG = "FfmpegRuntime"

/**
 * Менеджер ffmpeg, который можно обновлять.
 *
 * Раскладка в `filesDir/ffmpeg`:
 * ```
 * <версия>/real/ffmpeg, real/ffprobe   настоящие бинарники (ELF из репозитория Termux)
 * <версия>/lib/ (so-файлы) + симлинки soname  библиотеки этой сборки
 * <версия>/bin/ffmpeg, bin/ffprobe     симлинки на nativeLibraryDir/libffmpeg.so
 * current -> <версия>                  что используем сейчас
 * ```
 *
 * Почему так: Android 10+ запрещает `execve` файлов из каталога данных приложения
 * (проверено: `error=13, Permission denied`), но разрешает запуск из `nativeLibraryDir`
 * и через `/system/bin/linker64`. Поэтому `bin/ffmpeg` — симлинк на нативный лаунчер
 * [LAUNCHER] из APK (`native/fflaunch.c`, собран как `libffmpeg.so` — то же имя жёстко
 * зашито в youtubedl-android), а тот подменяет процесс на
 * `linker64 <версия>/real/ffmpeg "$@"` с очищенным окружением. Для yt-dlp это
 * неотличимо от обычного ffmpeg, поэтому обновление ffmpeg сводится к распаковке
 * новой версии и переключению симлинка `current`.
 *
 * Все правки ELF (RUNPATH → `$ORIGIN`, `libc++_shared.so` → [ElfPatch.LIBCXX_RENAMED])
 * выполняются здесь, на этапе установки, поэтому одинаково действуют и для вшитой
 * сборки, и для скачанной апдейтером.
 */
object FfmpegRuntime {
  private const val ASSET_ROOT = "ffmpeg9"
  private const val LAUNCHER = "libffmpeg.so"
  private const val ROOT_DIR = "ffmpeg"

  /**
   * Версия раскладки каталога `files/ffmpeg/<версия>`. Файл-маркер [LAYOUT_FILE] пишется при
   * каждой установке; если его нет или номер отличается — установка переделывается целиком.
   * Так обновление APK само чинит уже развёрнутые версии: 2 — libc++ переименована
   * в [ElfPatch.LIBCXX_RENAMED], 1 (без маркера) — старое имя `libc++_shared.so`.
   */
  private const val LAYOUT_VERSION = "2"
  private const val LAYOUT_FILE = "layout"

  /** Версия ffmpeg, вшитая в APK (`assets/ffmpeg9/version.txt`). */
  fun bundledVersion(context: Context): String =
    runCatching {
        context.assets.open("$ASSET_ROOT/version.txt").bufferedReader().use { it.readLine().trim() }
      }
      .getOrNull()
      .orEmpty()
      .ifBlank { "0.0.0" }

  private fun rootDir(context: Context) = File(context.filesDir, ROOT_DIR)

  private fun currentLink(context: Context) = File(rootDir(context), "current")

  /** Версия, на которую указывает `current`, либо `null`, если ffmpeg ещё не установлен. */
  fun currentVersion(context: Context): String? {
    val link = currentLink(context)
    if (!link.exists()) return null
    return runCatching { link.canonicalFile.name }.getOrNull()
  }

  /** Каталог с симлинками `ffmpeg`/`ffprobe` — его передаём в `yt-dlp --ffmpeg-location`. */
  fun binDir(context: Context): File = File(currentLink(context), "bin")

  fun isReady(context: Context): Boolean =
    File(binDir(context), "ffmpeg").exists() && currentVersion(context) != null

  /**
   * Разворачивает вшитую сборку, если рабочей версии ещё нет (или [force] — переустановка
   * именно вшитой версии). Возвращает версию, которая стала текущей.
   *
   * Уже установленная версия сохраняется, даже если она не равна вшитой: иначе «Обновить
   * ffmpeg» (версия из Termux) откатывалось бы обратно при следующем запуске приложения.
   * Если у установленной версии старая раскладка (нет маркера [LAYOUT_FILE]) — ставим вшитую:
   * переделать чужую сборку нечем, а старая раскладка ломает склейку.
   */
  fun ensureInstalled(context: Context, force: Boolean = false): String {
    val bundled = bundledVersion(context)
    val root = rootDir(context)
    val launcher = File(context.applicationInfo.nativeLibraryDir, LAUNCHER)
    val installed = currentVersion(context)
    val installedDir = installed?.let { File(root, it) }
    val installedReady =
      !force &&
        installedDir != null &&
        File(installedDir, "real/ffmpeg").exists() &&
        layoutVersion(installedDir) == LAYOUT_VERSION

    if (installedReady && installedDir != null && installed != null) {
      val bin = File(installedDir, "bin")
      if (File(bin, "ffmpeg").exists()) {
        if (installed == bundled) {
          Log.i(TAG, "ffmpeg $installed уже установлен: ${installedDir.absolutePath}")
        } else {
          Log.i(TAG, "используется установленная версия ffmpeg $installed (вшитая $bundled)")
        }
        return installed
      }
      // После обновления APK (`install -r`) nativeLibraryDir меняется, и симлинки
      // bin/ffmpeg → nativeLibraryDir/libffmpeg.so «повисают». Пересоздаём только их,
      // реальные бинарники и lib/* не трогаем.
      Log.i(TAG, "ffmpeg $installed: пересоздаю симлинки лаунчера (сменился nativeLibraryDir)")
      check(launcher.exists()) { "нет $LAUNCHER в ${launcher.parent}" }
      bin.mkdirs()
      for (exe in listOf("ffmpeg", "ffprobe")) {
        val path = File(bin, exe)
        if (isSymlink(path) || path.exists()) path.delete()
        Os.symlink(launcher.absolutePath, path.absolutePath)
      }
      return installed
    }
    check(launcher.exists()) { "нет $LAUNCHER в ${launcher.parent}" }

    val version = bundled
    val target = File(root, version)
    Log.i(TAG, "распаковка ffmpeg $version → ${target.absolutePath}")
    val link = currentLink(context)
    if (link.exists() || isSymlink(link)) link.delete()
    target.deleteRecursively()

    val real = File(target, "real").apply { mkdirs() }
    for (exe in listOf("ffmpeg", "ffprobe")) {
      context.assets.open("$ASSET_ROOT/$exe").use { input ->
        writePatched(input, File(real, exe), ElfPatch.RUNPATH_BINARY)
      }
    }

    val lib = File(target, "lib").apply { mkdirs() }
    val libAssets = context.assets.list("$ASSET_ROOT/lib").orEmpty()
    for (name in libAssets) {
      if (name == "symlinks.txt") continue
      context.assets.open("$ASSET_ROOT/lib/$name").use { input ->
        writePatched(input, File(lib, ElfPatch.fileName(name)), ElfPatch.RUNPATH_LIBRARY)
      }
    }

    // soname → реальный файл: линкер ищет именно soname, поэтому нужны симлинки.
    val symlinkLines =
      runCatching {
          context.assets.open("$ASSET_ROOT/lib/symlinks.txt").bufferedReader().use { it.readLines() }
        }
        .getOrDefault(emptyList())
    symlinkLines.forEach { line ->
      val parts = line.split("\t")
      if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
        val linkPath = File(lib, ElfPatch.fileName(parts[0]))
        if (isSymlink(linkPath) || linkPath.exists()) linkPath.delete()
        runCatching { Os.symlink(ElfPatch.fileName(parts[1]), linkPath.absolutePath) }
          .onFailure { Log.w(TAG, "symlink ${parts[0]} → ${parts[1]}: ${it.message}") }
      }
    }

    val bin = File(target, "bin").apply { mkdirs() }
    for (exe in listOf("ffmpeg", "ffprobe")) {
      Os.symlink(launcher.absolutePath, File(bin, exe).absolutePath)
    }

    writeLayout(target)
    activate(context, version, target)
    Log.i(TAG, "ffmpeg $version установлен, файлов в lib: ${lib.list()?.size ?: 0}")
    return version
  }

  /** Версия, которая развёрнута и активна (`current`), иначе `null`. */
  fun installedVersion(context: Context): String? =
    currentVersion(context)?.takeIf { File(rootDir(context), "$it/real/ffmpeg").exists() }

  /**
   * Ставит скачанную сборку: [realDir] содержит `ffmpeg`/`ffprobe`, [libDir] — `.so` и
   * [sonames] — пары «soname → реальный файл» (симлинки создаём сами).
   */
  fun installDownloaded(
    context: Context,
    version: String,
    realDir: File,
    libDir: File,
    sonames: List<Pair<String, String>>,
  ): String {
    val launcher = File(context.applicationInfo.nativeLibraryDir, LAUNCHER)
    check(launcher.exists()) { "нет $LAUNCHER в ${launcher.parent}" }
    check(File(realDir, "ffmpeg").exists()) { "в загрузке нет ffmpeg" }

    val target = File(rootDir(context), version)
    target.deleteRecursively()
    val real = File(target, "real").apply { mkdirs() }
    val lib = File(target, "lib").apply { mkdirs() }
    realDir.listFiles().orEmpty().forEach { entry ->
      entry.inputStream().use { writePatched(it, File(real, entry.name), ElfPatch.RUNPATH_BINARY) }
    }
    libDir.listFiles().orEmpty().forEach { entry ->
      entry.inputStream()
        .use { writePatched(it, File(lib, ElfPatch.fileName(entry.name)), ElfPatch.RUNPATH_LIBRARY) }
    }

    sonames.forEach { (soname, realName) ->
      val linkPath = File(lib, ElfPatch.fileName(soname))
      if (isSymlink(linkPath) || linkPath.exists()) linkPath.delete()
      runCatching { Os.symlink(ElfPatch.fileName(realName), linkPath.absolutePath) }
        .onFailure { Log.w(TAG, "symlink $soname → $realName: ${it.message}") }
    }

    val bin = File(target, "bin").apply { mkdirs() }
    for (exe in listOf("ffmpeg", "ffprobe")) {
      val path = File(bin, exe)
      if (isSymlink(path) || path.exists()) path.delete()
      Os.symlink(launcher.absolutePath, path.absolutePath)
    }

    writeLayout(target)
    activate(context, version, target)
    realDir.deleteRecursively()
    libDir.deleteRecursively()
    Log.i(TAG, "ffmpeg $version установлен из загрузки")
    return version
  }

  /** Переключает `current` на [target] и удаляет прочие версии. */
  private fun activate(context: Context, version: String, target: File) {
    val root = rootDir(context)
    val link = currentLink(context)
    if (isSymlink(link) || link.exists()) link.delete()
    Os.symlink(target.absolutePath, link.absolutePath)
    root.listFiles()?.forEach { entry ->
      if (entry.name != "current" && entry.name != version) entry.deleteRecursively()
    }
  }

  /** `ffmpeg -version` через лаунчер — то же, что увидит yt-dlp. */
  fun version(context: Context): String? {
    val binary = File(binDir(context), "ffmpeg")
    if (!binary.exists()) return null
    return runCatching {
        val process = ProcessBuilder(binary.absolutePath, "-version").redirectErrorStream(true).start()
        val firstLine = process.inputStream.bufferedReader().use { it.readLine() }.orEmpty()
        val exit = process.waitFor()
        Log.i(TAG, "ffmpeg -version exit=$exit → $firstLine")
        check(exit == 0 && firstLine.startsWith("ffmpeg version")) { "ffmpeg не запустился: $firstLine" }
        Regex("version\\s+(\\S+)").find(firstLine)?.groupValues?.get(1)
      }
      .onFailure { Log.w(TAG, "ffmpeg -version failed", it) }
      .getOrNull()
  }

  private fun isSymlink(file: File): Boolean =
    runCatching {
        (Os.lstat(file.absolutePath).st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK
      }
      .getOrDefault(false)

  /** Версия раскладки в каталоге [target] (`null` — маркера нет, установка «старая»). */
  private fun layoutVersion(target: File): String? =
    runCatching { File(target, LAYOUT_FILE).readText().trim() }.getOrNull()

  /** Пишет маркер раскладки — по нему [ensureInstalled] решает, нужна ли переустановка. */
  private fun writeLayout(target: File) {
    runCatching { File(target, LAYOUT_FILE).writeText(LAYOUT_VERSION) }
      .onFailure { Log.w(TAG, "маркер раскладки не записан: ${it.message}") }
  }

  /**
   * Записывает ELF в [target], применяя [ElfPatch]: RUNPATH Termux → [$runpath] и
   * `libc++_shared.so` → [ElfPatch.LIBCXX_RENAMED]. Обе замены «на месте», размер файла
   * не меняется; хвост исходной строки забивается нулями (строка всё равно NUL-терминирована).
   */
  private fun writePatched(input: InputStream, target: File, runpath: String) {
    val bytes = input.readBytes()
    if (!ElfPatch.replaceCString(bytes, ElfPatch.TERMUX_RUNPATH, runpath)) {
      // Не ELF или другой сборки — пишем как есть (для ffmpeg это повод для ошибки, но не падения).
      Log.w(TAG, "${target.name}: RUNPATH Termux не найден, копирую без правки")
    }
    if (ElfPatch.replaceCString(bytes, ElfPatch.LIBCXX, ElfPatch.LIBCXX_RENAMED)) {
      Log.i(TAG, "${target.name}: libc++ → ${ElfPatch.LIBCXX_RENAMED}")
    }
    target.parentFile?.mkdirs()
    target.outputStream().use { it.write(bytes) }
    target.setExecutable(true, false)
  }
}
