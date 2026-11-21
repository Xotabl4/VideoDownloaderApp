package org.xotabl4.ytdlpgui.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Тесты имени папки плейлиста. */
class PlaylistFormatsTest {
  @Test
  fun `playlist folder keeps readable title`() {
    assertEquals(
      "Аниму топы (аниме-сериал, 1993-2022)",
      DownloadStorage.sanitizeDir("Аниму топы (аниме-сериал, 1993-2022)"),
    )
  }

  @Test
  fun `forbidden characters in folder name replaced`() {
    assertEquals("a_b_c", DownloadStorage.sanitizeDir("""a/b:c"""))
  }

  @Test
  fun `blank folder name stays blank`() {
    assertEquals("", DownloadStorage.sanitizeDir(" ... "))
  }
}
