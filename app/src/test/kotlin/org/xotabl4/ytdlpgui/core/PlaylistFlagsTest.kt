package org.xotabl4.ytdlpgui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Флаг плейлиста в истории и задачах: без него «Повторить» качало плейлист как одно видео.
 *
 * encode и decode чистые, поэтому проверяются в JVM-тестах.
 */
class PlaylistFlagsTest {
  @Test
  fun `history entry keeps playlist flags`() {
    val entry =
      HistoryEntry(
        id = "1",
        title = "Аниму топы",
        displayPath = "Download/Аниму топы/Серия 1.mp4",
        uri = "content://media/external_primary/downloads/1",
        mimeType = "video/mp4",
        savedAt = 1_700_000_000_000,
        sizeBytes = 123,
        url = "https://rutube.ru/plst/1842354/",
        formatId = "default",
        conversion = "Оригинал",
        thumbnail = "https://img/1.jpg",
        durationSeconds = 42,
        isPlaylist = true,
        playlistTitle = "Аниму топы",
      )

    val decoded = DownloadHistory.decode(DownloadHistory.encode(listOf(entry))).single()
    assertEquals(entry, decoded)
    assertTrue(decoded.isPlaylist)
    assertEquals("Аниму топы", decoded.playlistTitle)
  }

  @Test
  fun `old history record without playlist fields decodes as single video`() {
    // Старая запись: 12 полей, без isPlaylist и playlistTitle.
    val old = listOf("1", "1700000000000", "10", "video/mp4", "Видео", "Download/a.mp4", "content://x", "", "", "", "", "0")
    val decoded = DownloadHistory.decode(old.joinToString("\t")).single()
    assertEquals(false, decoded.isPlaylist)
    assertEquals("", decoded.playlistTitle)
  }

  @Test
  fun `download task keeps playlist flags`() {
    val task =
      DownloadTask(
        id = "t1",
        title = "Плейлист",
        url = "https://vk.com/video/playlist/-1_2",
        formatId = "dash_sep-5+dash_sep-10",
        conversion = "",
        thumbnail = "",
        durationSeconds = 0,
        status = TaskStatus.Canceled,
        percent = 33,
        message = "",
        updatedAt = 1_700_000_000_000,
        isPlaylist = true,
        playlistTitle = "Киноканал FAN — VK плейлист 2",
      )

    val decoded = DownloadTasks.decode(DownloadTasks.encode(listOf(task))).single()
    assertEquals(task, decoded)
    assertTrue(decoded.isPlaylist)
    assertEquals("Киноканал FAN — VK плейлист 2", decoded.playlistTitle)
  }

  @Test
  fun `old task record decodes as single video`() {
    val old = listOf("t1", "1700000000000", "failed", "10", "Видео", "https://x", "18", "", "", "0", "ошибка")
    val decoded = DownloadTasks.decode(old.joinToString("\t")).single()
    assertEquals(false, decoded.isPlaylist)
    assertEquals("", decoded.playlistTitle)
  }
}
