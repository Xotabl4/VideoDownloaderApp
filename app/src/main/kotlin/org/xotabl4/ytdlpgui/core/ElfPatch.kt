package org.xotabl4.ytdlpgui.core

/**
 * Правки ELF сборки ffmpeg из Termux. Делаются при установке, поэтому одинаково
 * применяются к вшитой версии и к скачанной апдейтером.
 *
 * 1. `RUNPATH` Termux (`/data/data/com.termux/files/usr/lib`) меняется на `$ORIGIN`-путь.
 * 2. `libc++_shared.so` переименовывается в [LIBCXX_RENAMED] в `DT_NEEDED` и `DT_SONAME`.
 *    youtubedl-android запускает yt-dlp с `LD_LIBRARY_PATH` на свой Python-каталог, где
 *    лежит старая libc++ без символа `__from_chars_floating_point`; линкер переиспользует
 *    её по soname, и склейка падает. Уникальное имя убирает такую коллизию.
 *
 * Обе замены идут «на месте»: новая строка не длиннее старой, хвост забивается нулями.
 */
internal object ElfPatch {
  const val TERMUX_RUNPATH = "/data/data/com.termux/files/usr/lib"

  /** RUNPATH для `real/ffmpeg` и `real/ffprobe` (лежат в `<версия>/real`). */
  const val RUNPATH_BINARY = "\$ORIGIN/../lib"

  /** RUNPATH для библиотек из `<версия>/lib` — ищут рядом с собой. */
  const val RUNPATH_LIBRARY = "\$ORIGIN"

  /** Имя, которое libc++ носит в сборке Termux (16 символов — предел для замены). */
  const val LIBCXX = "libc++_shared.so"

  /** Наше уникальное имя: не совпадает ни с python'овской, ни с системной libc++. */
  const val LIBCXX_RENAMED = "libcxxff.so"

  /** Имя файла после правки: единственное переименование, остальные не трогаем. */
  fun fileName(name: String): String = if (name == LIBCXX) LIBCXX_RENAMED else name

  /** Полная правка одного ELF: RUNPATH + переименование libc++. Меняет [bytes] на месте. */
  fun patch(bytes: ByteArray, runpath: String): ByteArray {
    replaceCString(bytes, TERMUX_RUNPATH, runpath)
    replaceCString(bytes, LIBCXX, LIBCXX_RENAMED)
    return bytes
  }

  /**
   * Заменяет все вхождения C-строки [old] (с завершающим NUL) на [new] в [bytes].
   * [new] не длиннее [old] — остаток забивается нулями, чтобы не сдвигать таблицу строк.
   * Возвращает `true`, если хотя бы одно вхождение найдено.
   */
  fun replaceCString(bytes: ByteArray, old: String, new: String): Boolean {
    require(new.length <= old.length) { "замена «$new» длиннее исходной «$old»" }
    if (old.isEmpty()) return false
    val needle = (old + '\u0000').toByteArray(Charsets.US_ASCII)
    val replacement = (new + '\u0000').toByteArray(Charsets.US_ASCII)
    var replaced = false
    var index = indexOfBytes(bytes, needle, 0)
    while (index >= 0) {
      replacement.copyInto(bytes, index)
      for (position in index + replacement.size until index + needle.size) bytes[position] = 0
      replaced = true
      index = indexOfBytes(bytes, needle, index + needle.size)
    }
    return replaced
  }

  private fun indexOfBytes(bytes: ByteArray, needle: ByteArray, from: Int): Int {
    if (needle.isEmpty() || needle.size > bytes.size) return -1
    outer@ for (start in from..bytes.size - needle.size) {
      for (offset in needle.indices) {
        if (bytes[start + offset] != needle[offset]) continue@outer
      }
      return start
    }
    return -1
  }
}
