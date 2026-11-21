package org.xotabl4.ytdlpgui.core

/** Прогресс одной серии плейлиста: для «шторки» в «Загрузках». */
data class PlaylistItemProgress(
  /** Номер серии по порядку в плейлисте, с единицы. */
  val index: Int,
  /** Название серии; пусто, пока yt-dlp его не сообщил. */
  val title: String = "",
  /** Прогресс серии, 0…100. */
  val percent: Int = 0,
  /** Серия уже сохранена. */
  val done: Boolean = false,
  /** Серия не скачалась (ошибка или отмена). */
  val failed: Boolean = false,
)

/**
 * Служебные строки yt-dlp про серии плейлиста.
 *
 * yt-dlp печатает «[download] Downloading item 3 of 12», но из этой строки не видно названия серии,
 * а из прогресса — к какой серии он относится. Поэтому в запрос добавлены два `--print`
 * с метками `@@item` / `@@file`: первая приходит до загрузки серии, вторая — после сохранения файла.
 */
object PlaylistOutput {
  const val ITEM_PREFIX = "@@item\t"
  const val FILE_PREFIX = "@@file\t"

  /** `--print`: номер и название серии до начала её загрузки. */
  const val ITEM_TEMPLATE = "before_dl:@@item\t%(playlist_index)s\t%(title)s"

  /** `--print`: номер серии и путь готового файла. */
  const val FILE_TEMPLATE = "after_move:@@file\t%(playlist_index|)s\t%(filepath)s"

  /** `@@item<N>\t<title>` */
  data class ItemLine(val index: Int, val title: String)

  /** `@@file<N>\t<path>`; номер может быть пустым. */
  data class FileLine(val index: Int?, val path: String)

  fun parseItem(line: String): ItemLine? {
    if (!line.startsWith(ITEM_PREFIX)) return null
    val rest = line.removePrefix(ITEM_PREFIX)
    val index = rest.substringBefore('\t').trim().toIntOrNull() ?: return null
    val title = rest.substringAfter('\t', "").trim()
    return ItemLine(index, title)
  }

  fun parseFile(line: String): FileLine? {
    if (!line.startsWith(FILE_PREFIX)) return null
    val rest = line.removePrefix(FILE_PREFIX)
    val index = rest.substringBefore('\t').trim().toIntOrNull()
    val path = rest.substringAfter('\t', "").trim()
    if (path.isEmpty()) return null
    return FileLine(index, path)
  }
}


/**
 * Счётчик серий плейлиста по строкам yt-dlp.
 *
 * yt-dlp качает серии по очереди: одна серия — один поток загрузки. Общий процент считаем как
 * среднее по всем сериям, иначе полоса прыгала бы со 100 % на 0 % при переходе к следующей серии.
 *
 * Класс не зависит от Android — покрыт обычными JVM-тестами.
 */
class PlaylistProgress(totalHint: Int = 0) {
  private class Item(var title: String = "", var percent: Int = 0, var done: Boolean = false)

  private val lock = Any()
  private val items = LinkedHashMap<Int, Item>()

  /** Сколько серий в плейлисте; 0 — пока неизвестно. */
  private var total = totalHint.coerceAtLeast(0)

  /** Номер серии, которая качается сейчас; 0 — ещё не начинали. */
  private var current = 0

  /** Разбирает строку вывода yt-dlp. `true` — состояние изменилось. */
  fun onLine(line: String): Boolean =
    synchronized(lock) {
      onItem(line) || onFile(line) || onCounter(line) || onPercent(line)
    }

  private fun onItem(line: String): Boolean {
    val item = PlaylistOutput.parseItem(line) ?: return false
    val entry = items.getOrPut(item.index) { Item() }
    if (item.title.isNotBlank()) entry.title = item.title
    finishBefore(item.index)
    current = item.index
    if (total < item.index) total = item.index
    return true
  }

  private fun onFile(line: String): Boolean {
    val file = PlaylistOutput.parseFile(line) ?: return false
    val index = file.index ?: current.takeIf { it > 0 } ?: return false
    val entry = items.getOrPut(index) { Item() }
    entry.percent = 100
    entry.done = true
    finishBefore(index)
    if (current < index) current = index
    if (total < index) total = index
    return true
  }

  private fun onCounter(line: String): Boolean {
    val match = ITEM_COUNTER.find(line) ?: return false
    val index = match.groupValues[1].toIntOrNull() ?: return false
    val count = match.groupValues[2].toIntOrNull() ?: 0
    if (count > 0) total = count
    if (index <= 0) return count > 0
    finishBefore(index)
    current = index
    items.getOrPut(index) { Item() }
    if (total < index) total = index
    return true
  }

  private fun onPercent(line: String): Boolean {
    if (current <= 0) return false
    val value =
      PERCENT_LINE.find(line)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()?.toInt()
        ?: return false
    val entry = items.getOrPut(current) { Item() }
    // Внутри серии прогресс только растёт: видео и аудио качаются отдельными потоками.
    if (value <= entry.percent) return false
    entry.percent = value.coerceIn(0, 100)
    if (entry.percent >= 100) entry.done = true
    return true
  }

  /** Все серии до [index] уже позади (yt-dlp идёт по порядку). */
  private fun finishBefore(index: Int) {
    items.forEach { (key, item) ->
      if (key < index && !item.done) {
        item.done = true
        item.percent = 100
      }
    }
  }

  /** Строки для «шторки»: известные серии и ещё не начатые заглушки. */
  fun snapshot(): List<PlaylistItemProgress> =
    synchronized(lock) {
      val last = lastIndex()
      if (last <= 0) return emptyList()
      (1..last).map { index ->
        val item = items[index]
        val impliedDone = index < current
        PlaylistItemProgress(
          index = index,
          title = item?.title.orEmpty(),
          percent =
            when {
              item != null -> item.percent
              impliedDone -> 100
              else -> 0
            },
          done = item?.done == true || impliedDone,
        )
      }
    }

  /** Общий прогресс плейлиста, 0…100. */
  fun overallPercent(): Int =
    synchronized(lock) {
      val last = lastIndex()
      if (last <= 0) return 0
      var sum = 0
      for (index in 1..last) {
        val item = items[index]
        sum +=
          when {
            item != null -> item.percent
            index < current -> 100
            else -> 0
          }
      }
      ((sum.toFloat() / last) + 0.5f).toInt().coerceIn(0, 100)
    }

  /** Есть ли уже данные, по которым можно считать общий процент. */
  fun hasData(): Boolean = synchronized(lock) { lastIndex() > 0 }

  /** Номер текущей серии или `null`, если ещё не начинали. */
  fun currentIndex(): Int? = synchronized(lock) { current.takeIf { it > 0 } }

  /** Сколько всего серий; 0 — пока неизвестно. */
  fun totalCount(): Int = synchronized(lock) { lastIndex() }

  /** Название текущей серии, если yt-dlp его уже сообщил. */
  fun currentTitle(): String = synchronized(lock) { items[current]?.title.orEmpty() }

  private fun lastIndex(): Int = maxOf(total, items.keys.maxOrNull() ?: 0)

  private companion object {
    /** `[download] Downloading item 3 of 12` (у старых версий — `video`). */
    val ITEM_COUNTER = Regex("""Downloading (?:item|video) (\d+)(?:\s+of\s+(\d+))?""")

    /** `[download]  45.5% of ...` — только строки прогресса, а не проценты в названиях. */
    val PERCENT_LINE = Regex("""^\[download]\s+(\d{1,3}(?:[.,]\d+)?)%""")
  }
}
