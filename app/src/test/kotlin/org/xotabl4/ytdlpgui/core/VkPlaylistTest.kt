package org.xotabl4.ytdlpgui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор ссылок на плейлисты VK ([VkPlaylist.parse]) — по ним приложение добирает
 * название подборки и число видео, которых нет в `yt-dlp -J` (там `title=null`).
 *
 * Сам запрос `al_video.php` в JVM-тестах не проверить (нужна сеть и `org.json`),
 * он проверяется на устройстве.
 */
class VkPlaylistTest {
  @Test
  fun `classic vk playlist url`() {
    val ref = VkPlaylist.parse("https://vk.com/video/playlist/-174476437_2")
    assertEquals(VkPlaylist.Ref("-174476437", "2"), ref)
  }

  @Test
  fun `vkvideo playlist url`() {
    val ref = VkPlaylist.parse("https://vkvideo.ru/playlist/-51890028_-2")
    assertEquals(VkPlaylist.Ref("-51890028", "-2"), ref)
  }

  @Test
  fun `vk ru and mobile hosts`() {
    assertEquals(
      VkPlaylist.Ref("-204353299", "426"),
      VkPlaylist.parse("https://vk.ru/video/playlist/-204353299_426"),
    )
    assertEquals(
      VkPlaylist.Ref("-174476437", "2"),
      VkPlaylist.parse("https://m.vk.com/video/playlist/-174476437_2?rev=1"),
    )
    assertEquals(
      VkPlaylist.Ref("174476437", "7"),
      VkPlaylist.parse("http://new.vkvideo.ru/playlist/174476437_7"),
    )
  }

  @Test
  fun `single video and rutube playlist are not vk playlists`() {
    assertNull(VkPlaylist.parse("https://vk.com/video-127553155_456242961"))
    assertNull(VkPlaylist.parse("https://vkvideo.ru/video-204353299_456242961?list=abc"))
    assertNull(VkPlaylist.parse("https://rutube.ru/plst/1842354/"))
    assertNull(VkPlaylist.parse("https://www.youtube.com/playlist?list=PL123"))
    assertNull(VkPlaylist.parse(""))
  }

  @Test
  fun `windows-1251 payload is decoded`() {
    val text = "Киноканал FAN — VK плейлист 2"
    assertEquals(text, VkPlaylist.decode(text.toByteArray(charset("windows-1251"))))
  }

  @Test
  fun `utf-8 payload is decoded`() {
    val text = "Киноканал FAN — VK плейлист 2"
    assertEquals(text, VkPlaylist.decode(text.toByteArray(Charsets.UTF_8)))
  }

  @Test
  fun `matches mirrors parse`() {
    assertTrue(VkPlaylist.matches("https://vk.com/video/playlist/-174476437_2"))
    assertFalse(VkPlaylist.matches("https://vk.com/video-174476437_456241207"))
  }
}
