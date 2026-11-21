package org.xotabl4.ytdlpgui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тесты счётчика серий плейлиста: по строкам yt-dlp строится «шторка» в «Загрузках». */
class PlaylistProgressTest {
  private fun item(index: Int, title: String = "Серия $index"): String =
    PlaylistOutput.ITEM_PREFIX + index + "\t" + title

  private fun file(index: Int, path: String = "/data/tmp/$index.mp4"): String =
    PlaylistOutput.FILE_PREFIX + index + "\t" + path

  @Test
  fun `item line carries index and title`() {
    val parsed = PlaylistOutput.parseItem("@@item\t3\tСерия про море")
    assertEquals(PlaylistOutput.ItemLine(3, "Серия про море"), parsed)
    assertNull(PlaylistOutput.parseItem("@@item\tNA\tСерия"))
    assertNull(PlaylistOutput.parseItem("[download] 10% of 1MiB"))
  }

  @Test
  fun `file line parses index and empty index`() {
    assertEquals(PlaylistOutput.FileLine(2, "/tmp/a.mp4"), PlaylistOutput.parseFile("@@file\t2\t/tmp/a.mp4"))
    assertEquals(PlaylistOutput.FileLine(null, "/tmp/b.mp4"), PlaylistOutput.parseFile("@@file\t\t/tmp/b.mp4"))
    assertNull(PlaylistOutput.parseFile("@@file\t2\t"))
  }

  @Test
  fun `counter line fills placeholders up to total`() {
    val progress = PlaylistProgress()
    assertFalse(progress.hasData())

    assertTrue(progress.onLine("[download] Downloading item 1 of 12"))
    val snapshot = progress.snapshot()
    assertEquals(12, snapshot.size)
    assertEquals(1, progress.currentIndex())
    assertEquals(12, progress.totalCount())
    assertTrue(snapshot.none { it.done })
    assertEquals(0, progress.overallPercent())
  }

  @Test
  fun `percent belongs to current series and never goes back`() {
    val progress = PlaylistProgress(totalHint = 12)
    progress.onLine(item(1))
    progress.onLine("[download]  45.5% of 10MiB at 1MiB/s")
    assertEquals(45, progress.snapshot()[0].percent)
    assertEquals(4, progress.overallPercent())

    // Новый поток той же серии (видео/аудио) начинает с нуля — общий процент не должен падать.
    progress.onLine("[download]   2.0% of 2MiB")
    assertEquals(45, progress.snapshot()[0].percent)
  }

  @Test
  fun `new item starts from zero and previous one is done`() {
    val progress = PlaylistProgress(totalHint = 12)
    progress.onLine(item(1, "Первая"))
    progress.onLine(file(1))
    progress.onLine(item(2, "Вторая"))
    progress.onLine("[download]  10.0% of 10MiB")

    val snapshot = progress.snapshot()
    assertEquals(12, snapshot.size)
    assertTrue(snapshot[0].done)
    assertEquals(100, snapshot[0].percent)
    assertEquals("Первая", snapshot[0].title)
    assertFalse(snapshot[1].done)
    assertEquals(10, snapshot[1].percent)
    assertEquals("Вторая", snapshot[1].title)
    // (100 + 10) / 12 = 9.17 → 9
    assertEquals(9, progress.overallPercent())
  }

  @Test
  fun `file line without index marks current series done`() {
    val progress = PlaylistProgress(totalHint = 3)
    progress.onLine(item(2))
    progress.onLine(PlaylistOutput.FILE_PREFIX + "\t/data/tmp/x.mp4")

    val snapshot = progress.snapshot()
    assertTrue(snapshot[0].done)
    assertTrue(snapshot[1].done)
    assertFalse(snapshot[2].done)
    assertEquals(2, progress.currentIndex())
  }

  @Test
  fun `unknown total keeps only the running series`() {
    val progress = PlaylistProgress()
    progress.onLine(item(1, "Единственная"))
    progress.onLine("[download] 100% of 1MiB")

    assertEquals(1, progress.snapshot().size)
    assertEquals(100, progress.overallPercent())
  }

  @Test
  fun `series title from yt-dlp is used in snapshot`() {
    val progress = PlaylistProgress()
    progress.onLine(item(5, "Пятая серия"))
    assertEquals("Пятая серия", progress.currentTitle())
    assertEquals(5, progress.snapshot().size)
    assertEquals("Пятая серия", progress.snapshot().first { it.index == 5 }.title)
    // Серии до текущей yt-dlp уже прошёл — в «шторке» они отмечены готовыми.
    assertTrue(progress.snapshot().first { it.index == 4 }.done)
  }

  @Test
  fun `templates keep markers for parsing`() {
    assertTrue(PlaylistOutput.ITEM_TEMPLATE.startsWith("before_dl:" + PlaylistOutput.ITEM_PREFIX))
    assertTrue(PlaylistOutput.FILE_TEMPLATE.startsWith("after_move:" + PlaylistOutput.FILE_PREFIX))
  }
}
