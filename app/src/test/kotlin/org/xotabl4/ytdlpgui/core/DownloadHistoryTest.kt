package org.xotabl4.ytdlpgui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тесты истории загрузок. */
class DownloadHistoryTest {
  private fun entry(
    id: String,
    title: String = "Видео",
    path: String = "Download/$id.mp4",
    url: String = "https://example.com/$id",
  ) = HistoryEntry(
    id = id,
    title = title,
    displayPath = path,
    uri = "content://media/external/file/$id",
    mimeType = "video/mp4",
    savedAt = 1_700_000_000_000L + id.hashCode(),
    sizeBytes = 1024L * 1024,
    url = url,
    formatId = "137+140",
    conversion = "mp4",
  )

  @Test
  fun `encode then decode returns same entries`() {
    val entries = listOf(entry("a"), entry("b", title = "Второе видео", path = "Download/b.mkv"))

    assertEquals(entries, DownloadHistory.decode(DownloadHistory.encode(entries)))
  }

  @Test
  fun `tabs and newlines inside fields survive`() {
    val tricky = entry("x", title = "Строка\tс табом\nи переносом", path = "Download/путь\tс табом.mp4")

    val decoded = DownloadHistory.decode(DownloadHistory.encode(listOf(tricky)))

    assertEquals(tricky, decoded.single())
    assertEquals(1, DownloadHistory.encode(listOf(tricky)).lines().size)
  }

  @Test
  fun `backslashes are escaped`() {
    val tricky = entry("y", title = """C:\path\to\file""")

    assertEquals(tricky, DownloadHistory.decode(DownloadHistory.encode(listOf(tricky))).single())
  }

  @Test
  fun `new entries go first and replace same id`() {
    val old = entry("a")
    val same = old.copy(title = "Обновлённое название")
    val fresh = entry("b")

    val list = DownloadHistory.prepend(DownloadHistory.prepend(listOf(old), same), fresh)

    assertEquals(listOf("b", "a"), list.map { it.id })
    assertEquals("Обновлённое название", list[1].title)
  }

  @Test
  fun `history is capped at limit`() {
    var list = emptyList<HistoryEntry>()
    repeat(DownloadHistory.LIMIT + 5) { index -> list = DownloadHistory.prepend(list, entry("id$index")) }

    assertEquals(DownloadHistory.LIMIT, list.size)
    assertEquals("id${DownloadHistory.LIMIT + 4}", list.first().id)
  }

  @Test
  fun `broken lines are skipped`() {
    val raw = "мусор\n" + DownloadHistory.encode(listOf(entry("ok"))) + "\n\nещё_мусор"

    val decoded = DownloadHistory.decode(raw)

    assertEquals(1, decoded.size)
    assertEquals("ok", decoded.single().id)
  }

  @Test
  fun `entry keeps url and format for retry`() {
    val saved = SavedFile("Заголовок", "Download/t.mp4", "content://media/external/file/1", "video/mp4")

    val built =
      DownloadHistory.entryFor(
        title = "Заголовок",
        saved = saved,
        sizeBytes = 5L * 1024 * 1024,
        url = "https://youtu.be/x",
        formatId = "137+140",
        conversion = "mp4",
        now = 123L,
      )

    assertEquals("https://youtu.be/x", built.url)
    assertEquals("137+140", built.formatId)
    assertEquals("mp4", built.conversion)
    assertEquals(123L, built.savedAt)
    assertEquals("Download/t.mp4", built.displayPath)
    assertTrue(built.sizeBytes == 5L * 1024 * 1024)
  }

  @Test
  fun `thumbnail and duration survive encode and decode`() {
    val entry =
      entry("thumb").copy(thumbnail = "https://i.ytimg.com/vi/abc/hq.jpg", durationSeconds = 544L)

    val decoded = DownloadHistory.decode(DownloadHistory.encode(listOf(entry))).single()

    assertEquals("https://i.ytimg.com/vi/abc/hq.jpg", decoded.thumbnail)
    assertEquals(544L, decoded.durationSeconds)
  }

  @Test
  fun `old records without thumbnail fields still decode`() {
    // Формат до появления миниатюр: 7 полей, без url/format/conversion/thumbnail/duration.
    val legacy = listOf("id1", "1700000000000", "1048576", "video/mp4", "Старое", "Download/old.mp4", "content://x/1")
    val raw = legacy.joinToString("\t")

    val decoded = DownloadHistory.decode(raw).single()

    assertEquals("Старое", decoded.title)
    assertEquals("", decoded.thumbnail)
    assertEquals(0L, decoded.durationSeconds)
  }

  @Test
  fun `duration is formatted as timecode`() {
    assertEquals("", FormatsParser.formatDuration(0))
    assertEquals("12:34", FormatsParser.formatDuration(754))
    assertEquals("1:02:05", FormatsParser.formatDuration(3725))
  }
}
