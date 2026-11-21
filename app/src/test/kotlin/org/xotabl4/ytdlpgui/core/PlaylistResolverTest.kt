package org.xotabl4.ytdlpgui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тесты разбора списка серий для параллельной загрузки плейлиста. */
class PlaylistResolverTest {
  private fun line(url: String, webpageUrl: String, title: String): String =
    PlaylistResolver.ENTRY_PREFIX + url + "\t" + webpageUrl + "\t" + title

  @Test
  fun `parses entries in playlist order`() {
    val entries =
      PlaylistResolver.parseLines(
        "https://rutube.ru/plst/525046",
        listOf(
          line("https://rutube.ru/video/aaa/", "NA", "Серия 1"),
          line("NA", "https://rutube.ru/video/bbb/", "Серия 2"),
        ),
      )

    assertEquals(2, entries.size)
    assertEquals("https://rutube.ru/video/aaa/", entries[0].url)
    assertEquals("Серия 1", entries[0].title)
    assertEquals("https://rutube.ru/video/bbb/", entries[1].url)
  }

  @Test
  fun `builds rutube url from bare video id`() {
    assertEquals(
      "https://rutube.ru/video/1a2b3c/",
      PlaylistResolver.entryUrl("https://rutube.ru/plst/525046", "1a2b3c", "NA"),
    )
  }

  @Test
  fun `builds youtube url from video id`() {
    assertEquals(
      "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      PlaylistResolver.entryUrl("https://www.youtube.com/playlist?list=PL1", "dQw4w9WgXcQ", "NA"),
    )
  }

  @Test
  fun `skips duplicates and lines without url`() {
    val entries =
      PlaylistResolver.parseLines(
        "https://rutube.ru/plst/1",
        listOf(
          line("https://rutube.ru/video/a/", "NA", "Первая"),
          line("https://rutube.ru/video/a/", "NA", "Дубль"),
          line("NA", "NA", "Без ссылки"),
          "[download] Downloading item 1 of 2",
        ),
      )

    assertEquals(1, entries.size)
    assertEquals("Первая", entries.single().title)
  }

  @Test
  fun `unknown host without absolute url gives no entry`() {
    assertNull(PlaylistResolver.entryUrl("https://example.com/playlist/1", "abc123", "NA"))
  }

  @Test
  fun `template keeps marker for parsing`() {
    assertTrue(PlaylistResolver.ENTRY_TEMPLATE.startsWith(PlaylistResolver.ENTRY_PREFIX))
  }
}
