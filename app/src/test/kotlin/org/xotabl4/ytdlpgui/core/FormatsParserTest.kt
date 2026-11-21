package org.xotabl4.ytdlpgui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверяет разбор форматов: категории, склейку с аудио, сортировки и подписи размеров.
 */
class FormatsParserTest {

  @Test
  fun `resolution label uses known presets`() {
    assertEquals("Unknown", FormatsParser.resolutionLabel(0, 0))
    assertEquals("2160p 4K", FormatsParser.resolutionLabel(3840, 2160))
    assertEquals("1440p HD", FormatsParser.resolutionLabel(2560, 1440))
    assertEquals("1080p HD", FormatsParser.resolutionLabel(1920, 1080))
    assertEquals("720p", FormatsParser.resolutionLabel(1280, 720))
    assertEquals("480p", FormatsParser.resolutionLabel(854, 480))
    assertEquals("360p", FormatsParser.resolutionLabel(640, 360))
    assertEquals("240p", FormatsParser.resolutionLabel(426, 240))
    assertEquals("144p", FormatsParser.resolutionLabel(256, 144))
    assertEquals("100p", FormatsParser.resolutionLabel(100, 100))
  }

  @Test
  fun `size label matches python formatting`() {
    assertEquals("? MB", FormatsParser.sizeLabel(0))
    assertEquals("1 MB", FormatsParser.sizeLabel(1024L * 1024L))
    assertEquals("1.5 MB", FormatsParser.sizeLabel(1024L * 1024L * 3 / 2))
    assertEquals("10.3 MB", FormatsParser.sizeLabel((10.34 * 1024 * 1024).toLong()))
  }

  @Test
  fun `formats are split into three categories`() {
    val formats = FormatsParser.build("Test video", sampleFormats())

    assertEquals("Test video", formats.title)
    // Только видео и только аудио идут как есть, mp4 выше webm, m4a выше webm.
    assertEquals(listOf("137", "248"), formats.videoOnly.map { it.formatId })
    assertEquals(listOf("140", "251"), formats.audioOnly.map { it.formatId })
    // Видео + аудио: склеенный mp4 (137+140), готовый mp4 (18), затем webm (248+251).
    assertEquals(listOf("137+140", "18", "248+251"), formats.videoAudio.map { it.downloadId })
  }

  @Test
  fun `video only formats are merged with best matching audio`() {
    val formats = FormatsParser.build("Test video", sampleFormats())
    val merged = formats.videoAudio

    // mp4+1080p → m4a (137+140), затем нативный mp4 с аудио (18), затем webm+opus (248+251)
    assertEquals(listOf("137+140", "18", "248+251"), merged.map { it.downloadId })
    assertTrue(merged[0].shortDesc.startsWith("[mp4] 1080p HD - "))
    assertTrue(merged[0].shortDesc.endsWith("k - 105 MB"))
    assertEquals("V: avc1 / A: mp4a", merged[0].tooltip.lines().first().removePrefix("Кодеки: "))
    assertEquals("137+140", merged[0].downloadId)
  }

  @Test
  fun `video without any audio falls back to bestaudio`() {
    val onlyVideo =
      listOf(
        RawFormat(
          formatId = "299",
          ext = "mp4",
          vcodec = "avc1",
          acodec = "none",
          filesize = 10L * 1024 * 1024,
          filesizeApprox = null,
          width = 1920,
          height = 1080,
          tbr = 4000.0,
        )
      )

    val formats = FormatsParser.build("No audio", onlyVideo)
    assertEquals(1, formats.videoAudio.size)
    assertEquals("299+bestaudio/best", formats.videoAudio[0].downloadId)
    assertTrue(formats.videoAudio[0].tooltip.contains("Авто (Лучшее доступное)"))
    assertTrue(formats.audioOnly.isEmpty())
  }

  @Test
  fun `filesize approximation is used when size is unknown`() {
    val formats =
      FormatsParser.build(
        "Approx",
        listOf(
          RawFormat(
            formatId = "22",
            ext = "mp4",
            vcodec = "avc1",
            acodec = "mp4a",
            filesize = null,
            filesizeApprox = 50L * 1024 * 1024,
            width = 1280,
            height = 720,
            tbr = 1500.0,
          )
        ),
      )
    assertTrue(formats.videoAudio.single().shortDesc.endsWith("50 MB"))
  }

  @Test
  fun `rutube duplicates collapse to one per quality preferring direct mp4 over hls`() {
    val dup =
      listOf(
        rutube("m3u8-5673-0", 1080, 5673.0),
        rutube("m3u8-5673-1", 1080, 5673.0),
        rutube("default-5673-0", 1080, 5673.0),
        rutube("default-5673-1", 1080, 5673.0),
        rutube("default-292-0", 144, 292.0),
      )

    val formats = FormatsParser.build("RuTube", dup)

    // 4 дубля 1080p → один (прямой mp4 `default-*` вытесняет HLS `m3u8-*`), 144p остаётся.
    assertEquals(listOf("default-5673-0", "default-292-0"), formats.videoAudio.map { it.formatId })
    assertEquals(2, formats.videoAudio.size)
    assertTrue(formats.videoAudio[0].shortDesc.startsWith("[mp4] 1080p HD - "))
  }

  @Test
  fun `vk duplicates collapse keeping highest bitrate stream`() {
    fun vk(id: String, height: Int, fps: Double, tbr: Double, vcodec: String, ext: String = "mp4") =
      RawFormat(
        formatId = id,
        ext = ext,
        vcodec = vcodec,
        acodec = "none",
        filesize = null,
        filesizeApprox = null,
        width = height * 16 / 9,
        height = height,
        tbr = tbr,
        fps = fps,
        dynamicRange = "SDR",
      )

    val formats =
      FormatsParser.build(
        "VK",
        listOf(
          vk("hls_fmp4-18388", 2160, 60.0, 18388.0, "avc1.640034"),
          vk("dash_sep-8", 2160, 60.0, 24871.0, "avc1.640034"),
          vk("dash_webm-7", 2160, 60.0, 21504.0, "vp9", ext = "webm"),
          vk("dash_sep-6", 1080, 60.0, 8208.0, "avc1.64002A"),
          // тот же кодек и высота, но 30 fps — отдельная строка, а не дубль
          vk("dash_sep-6s", 1080, 30.0, 5000.0, "avc1.64002A"),
        ),
      )

    // 2160p mp4 схлопнулся в поток с большим битрейтом, а 1080p 60fps и 30fps
    // остались разными строками. Порядок: mp4 выше webm.
    assertEquals(
      listOf("dash_sep-8", "dash_sep-6", "dash_sep-6s", "dash_webm-7"),
      formats.videoOnly.map { it.formatId },
    )
    // SDR не пишем в подписи — только HDR-варианты (иначе «2160p SDR» шумит в списке).
    assertEquals("", formats.videoOnly[0].dynamicRange)
  }

  @Test
  fun `audio variants are not collapsed by dedupe`() {
    fun audio(id: String, codec: String, ext: String, tbr: Double) =
      RawFormat(
        formatId = id,
        ext = ext,
        vcodec = "none",
        acodec = codec,
        filesize = null,
        filesizeApprox = null,
        width = null,
        height = null,
        tbr = tbr,
      )

    val formats =
      FormatsParser.build(
        "VK",
        listOf(
          audio("dash_sep-9", "mp4a.40.2", "m4a", 36.0),
          audio("dash_sep-13", "mp4a.40.2", "m4a", 265.0),
          audio("dash_webm-9", "opus", "webm", 128.0),
          audio("dash_webm-10", "opus", "webm", 194.0),
        ),
      )

    // Дорожки отличаются только битрейтом — каждая должна остаться отдельной строкой.
    assertEquals(4, formats.audioOnly.size)
    assertEquals(listOf("dash_sep-13", "dash_sep-9", "dash_webm-10", "dash_webm-9"), formats.audioOnly.map { it.formatId })
  }

  private fun rutube(id: String, height: Int, tbr: Double): RawFormat =
    RawFormat(
      formatId = id,
      ext = "mp4",
      vcodec = if (height == 1080) "avc1.640029" else "avc1.42c014",
      acodec = "mp4a.40.2",
      filesize = null,
      filesizeApprox = null,
      width = if (height == 1080) 1920 else 256,
      height = height,
      tbr = tbr,
    )

  private fun sampleFormats(): List<RawFormat> =
    listOf(
      RawFormat(
        formatId = "137",
        ext = "mp4",
        vcodec = "avc1.640028",
        acodec = "none",
        filesize = 100L * 1024 * 1024,
        filesizeApprox = null,
        width = 1920,
        height = 1080,
        tbr = 4500.0,
      ),
      RawFormat(
        formatId = "248",
        ext = "webm",
        vcodec = "vp9",
        acodec = "none",
        filesize = 80L * 1024 * 1024,
        filesizeApprox = null,
        width = 1920,
        height = 1080,
        tbr = 3000.0,
      ),
      RawFormat(
        formatId = "140",
        ext = "m4a",
        vcodec = "none",
        acodec = "mp4a.40.2",
        filesize = 5L * 1024 * 1024,
        filesizeApprox = null,
        width = null,
        height = null,
        tbr = 128.0,
      ),
      RawFormat(
        formatId = "251",
        ext = "webm",
        vcodec = "none",
        acodec = "opus",
        filesize = 6L * 1024 * 1024,
        filesizeApprox = null,
        width = null,
        height = null,
        tbr = 160.0,
      ),
      RawFormat(
        formatId = "18",
        ext = "mp4",
        vcodec = "avc1.42001E",
        acodec = "mp4a.40.2",
        filesize = 20L * 1024 * 1024,
        filesizeApprox = null,
        width = 640,
        height = 360,
        tbr = 500.0,
      ),
    )
}
