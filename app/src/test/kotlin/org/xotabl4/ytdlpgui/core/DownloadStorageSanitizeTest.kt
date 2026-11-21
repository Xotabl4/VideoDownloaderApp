package org.xotabl4.ytdlpgui.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Тесты очистки имён файлов. */
class DownloadStorageSanitizeTest {
  @Test
  fun `forbidden characters replaced`() {
    assertEquals("a_b_c_d_e_f_g_h_i_j.mp4", DownloadStorage.sanitize("""a/b\c:d*e?f"g<h>i|j.mp4"""))
  }

  @Test
  fun `trailing dot of title does not double before extension`() {
    assertEquals(
      "iPad mini OLED унижает iPhone Duo 10 минут. Распаковал и всё понял.mp4",
      DownloadStorage.sanitize("iPad mini OLED унижает iPhone Duo 10 минут. Распаковал и всё понял..mp4"),
    )
  }

  @Test
  fun `trailing spaces and dots trimmed`() {
    assertEquals("Video.mp4", DownloadStorage.sanitize("Video. . .  .mp4"))
    assertEquals("Video", DownloadStorage.sanitize("Video..."))
  }

  @Test
  fun `name without extension kept`() {
    assertEquals("Самые опасные грузовики ⧸ War Thunder", DownloadStorage.sanitize("Самые опасные грузовики ⧸ War Thunder"))
  }

  @Test
  fun `empty or dot only name falls back`() {
    assertEquals("video", DownloadStorage.sanitize(""))
    assertEquals("video", DownloadStorage.sanitize(" ... "))
    assertEquals("video.mp4", DownloadStorage.sanitize(".mp4"))
  }

  @Test
  fun `long name truncated keeping extension within limit`() {
    val name = DownloadStorage.sanitize("x".repeat(300) + ".mp4")

    assertEquals("x".repeat(112) + ".mp4", name)
    assertEquals(true, name.length <= 120)
  }
}
