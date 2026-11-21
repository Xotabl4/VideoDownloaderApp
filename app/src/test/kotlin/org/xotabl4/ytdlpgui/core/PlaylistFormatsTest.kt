package org.xotabl4.ytdlpgui.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Имя подпапки плейлиста ([DownloadStorage.sanitizeDir]) — используется, когда ссылка ведёт
 * на плейлист: файлы складываются в `Download/<название плейлиста>/`.
 *
 * Разбор `yt-dlp -J` для плейлистов (`FormatsJson.parse`) в JVM-тестах не проверить: он завязан
 * на `org.json`, которого нет в unit-тестах без Robolectric. Проверено на устройстве
 * (RuTube, канал «Анимач», плейлист на 4 видео).
 */
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
