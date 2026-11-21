package org.xotabl4.ytdlpgui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты компактного выбора формата: контейнеры, «Автоматически» = лучшее качество,
 * сборка `-f`-id для раздельных потоков и ожидаемый размер файла.
 */
class FormatPickerTest {
  private fun item(
    id: String,
    ext: String,
    height: Int,
    tbr: Double,
    size: Long,
    video: Boolean = true,
    audio: Boolean = true,
  ) = FormatItem(
    formatId = id,
    shortDesc = "",
    tooltip = "",
    ext = ext,
    width = if (height > 0) height * 16 / 9 else 0,
    height = height,
    tbr = tbr,
    hasVideo = video,
    hasAudio = audio,
    downloadId = id,
    rawFilesize = size,
    vcodecShort = if (video) "avc1" else "None",
    acodecShort = if (audio) "mp4a" else "None",
  )

  @Test
  fun `containers are ordered mp4 first then webm`() {
    val items =
      listOf(
        item("1", "webm", 1080, 2000.0, 10),
        item("2", "mkv", 1080, 2000.0, 10),
        item("3", "mp4", 1080, 2000.0, 10),
      )

    assertEquals(listOf("mp4", "webm", "mkv"), FormatPicker.containers(items))
  }

  @Test
  fun `container filter keeps only requested ext`() {
    val items = listOf(item("1", "webm", 2160, 9000.0, 10), item("2", "mp4", 1080, 4000.0, 10))

    assertEquals(listOf("2"), FormatPicker.withContainer(items, "mp4").map { it.formatId })
    assertEquals(items, FormatPicker.withContainer(items, null))
  }

  @Test
  fun `best is highest resolution then bitrate regardless of container`() {
    val items =
      listOf(
        item("mp4-1080", "mp4", 1080, 4500.0, 10),
        item("webm-2160", "webm", 2160, 9000.0, 10),
        item("mp4-720", "mp4", 720, 2000.0, 10),
      )

    // VK-список: mp4 идёт первым в сортировке десктопа, но «Автоматически» обязан взять 4K.
    assertEquals("webm-2160", FormatPicker.best(items)?.formatId)
    assertEquals(
      listOf("webm-2160", "mp4-1080", "mp4-720"),
      FormatPicker.sortedByQuality(items).map { it.formatId },
    )
  }

  @Test
  fun `audio sorts by bitrate and has no badge`() {
    val audio =
      listOf(
        item("a-low", "m4a", 0, 64.0, 0, video = false),
        item("a-high", "m4a", 0, 129.0, 0, video = false),
      )

    assertEquals("a-high", FormatPicker.best(audio)?.formatId)
    assertEquals("129k", FormatPicker.qualityLabel(audio[1]))
    assertNull(FormatPicker.badge(audio[1]))
  }

  @Test
  fun `badges follow resolution`() {
    assertEquals("4K", FormatPicker.badge(item("1", "mp4", 2160, 9000.0, 0)))
    assertEquals("2K", FormatPicker.badge(item("1", "mp4", 1440, 5000.0, 0)))
    assertEquals("FHD", FormatPicker.badge(item("1", "mp4", 1080, 4000.0, 0)))
    assertEquals("HD", FormatPicker.badge(item("1", "mp4", 720, 2000.0, 0)))
    assertEquals("SD", FormatPicker.badge(item("1", "mp4", 480, 900.0, 0)))
    assertNull(FormatPicker.badge(item("1", "mp4", 360, 600.0, 0)))
  }

  @Test
  fun `separate streams are joined into one format id`() {
    val video = item("137", "mp4", 1080, 4500.0, 100)
    val audio = item("140", "m4a", 0, 128.0, 5, video = false)

    assertEquals("137+140", FormatPicker.mergedId(video, audio))
    assertEquals("137+bestaudio/best", FormatPicker.mergedId(video, null))
  }

  @Test
  fun `id for category picks the right streams`() {
    val combined = item("18", "mp4", 360, 500.0, 20)
    val video = item("137", "mp4", 1080, 4500.0, 100)
    val audio = item("140", "m4a", 0, 128.0, 5, video = false)

    assertEquals(
      "18",
      FormatPicker.idFor(FormatPicker.Kind.VideoAudio, combined, video, audio),
    )
    assertEquals(
      "137+140",
      FormatPicker.idFor(FormatPicker.Kind.VideoOnly, combined, video, audio),
    )
    assertEquals(
      "140",
      FormatPicker.idFor(FormatPicker.Kind.AudioOnly, combined, video, audio),
    )
    // Для конвертации нужен видео-поток: без него id не собирается, даже если аудио выбрано.
    assertNull(FormatPicker.idFor(FormatPicker.Kind.VideoOnly, combined, null, null))
    assertNull(FormatPicker.idFor(FormatPicker.Kind.VideoOnly, combined, null, audio))
    // В «Видео + аудио» без склеенного формата берётся выбранное видео + аудио.
    assertEquals(
      "137+140",
      FormatPicker.idFor(FormatPicker.Kind.VideoAudio, null, video, audio),
    )
  }

  @Test
  fun `expected size sums parts and reports unknown`() {
    val video = item("137", "mp4", 1080, 4500.0, 100L * 1024 * 1024)
    val audio = item("140", "m4a", 0, 128.0, 5L * 1024 * 1024, video = false)
    val unknown = item("299", "mp4", 1080, 4500.0, 0)

    assertEquals(105L * 1024 * 1024, FormatPicker.expectedBytes(listOf(video, audio)))
    assertEquals(0L, FormatPicker.expectedBytes(listOf(video, unknown)))
    assertEquals(0L, FormatPicker.expectedBytes(emptyList()))
    assertEquals(
      105L * 1024 * 1024,
      FormatPicker.bytesFor(FormatPicker.Kind.VideoOnly, null, video, audio),
    )
  }

  @Test
  fun `detail shows container codecs and size`() {
    val item = item("137", "mp4", 1080, 4500.0, 100L * 1024 * 1024)

    assertTrue(FormatPicker.detail(item).startsWith("mp4 · V: avc1 / A: mp4a · "))
    assertTrue(FormatPicker.detail(item).endsWith("100 MB"))
    assertTrue(FormatPicker.audioDetail(item).startsWith("A: mp4a · "))
  }

  @Test
  fun `quality title adds fps and hdr`() {
    val plain = item("1", "mp4", 1080, 4000.0, 0)
    val sixty = item("2", "mp4", 2160, 9000.0, 0).copy(fps = 60.0)
    val hdr = item("3", "mp4", 2160, 9000.0, 0).copy(fps = 60.0, dynamicRange = "HDR10")
    val fractional = item("4", "mp4", 1080, 4000.0, 0).copy(fps = 59.94)

    assertEquals("1080p", FormatPicker.qualityTitle(plain))
    assertEquals("2160p 60fps", FormatPicker.qualityTitle(sixty))
    assertEquals("2160p 60fps HDR10", FormatPicker.qualityTitle(hdr))
    assertEquals("1080p 59.94fps", FormatPicker.qualityTitle(fractional))
  }

  @Test
  fun `fps label covers integer fractional and unknown`() {
    assertEquals("30fps", FormatsParser.fpsLabel(30.0))
    assertEquals("60fps", FormatsParser.fpsLabel(60.0))
    assertEquals("59.94fps", FormatsParser.fpsLabel(59.94))
    assertNull(FormatsParser.fpsLabel(0.0))
  }
}
