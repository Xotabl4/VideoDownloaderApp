package org.xotabl4.ytdlpgui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты правок ELF-строк: замена RUNPATH Termux на `$ORIGIN` и переименование
 * `libc++_shared.so` → `libcxxff.so` (см. [ElfPatch]).
 */
class ElfPatchTest {
  private fun ascii(vararg parts: String): ByteArray =
    parts.joinToString(separator = "").toByteArray(Charsets.US_ASCII)

  private fun text(bytes: ByteArray): String = String(bytes, Charsets.US_ASCII)

  @Test
  fun `runpath replaced in place without growing file`() {
    val raw = ascii("head", ElfPatch.TERMUX_RUNPATH, "\u0000", "tail")

    assertTrue(ElfPatch.replaceCString(raw, ElfPatch.TERMUX_RUNPATH, ElfPatch.RUNPATH_BINARY))

    val result = text(raw)
    assertTrue(result.contains(ElfPatch.RUNPATH_BINARY + "\u0000"))
    assertFalse(result.contains(ElfPatch.TERMUX_RUNPATH))
    // Инвариант: замена не длиннее исходной строки, иначе слот в .dynstr не поместится.
    assertTrue(ElfPatch.RUNPATH_BINARY.length < ElfPatch.TERMUX_RUNPATH.length)
  }

  @Test
  fun `libcxx needed rewritten to unique name and padded with zeros`() {
    val raw = ascii("x", ElfPatch.LIBCXX, "\u0000y")
    val expectedSize = 1 + ElfPatch.LIBCXX.length + 1 + 1

    assertTrue(ElfPatch.replaceCString(raw, ElfPatch.LIBCXX, ElfPatch.LIBCXX_RENAMED))

    assertEquals(expectedSize, raw.size)
    val result = text(raw)
    assertTrue(result.contains(ElfPatch.LIBCXX_RENAMED + "\u0000"))
    assertFalse(result.contains(ElfPatch.LIBCXX))
    // Хвост исходного слота забит нулями: длина строки-слота не изменилась.
    assertEquals(0, raw[1 + ElfPatch.LIBCXX.length].toInt())
  }

  @Test
  fun `all occurrences replaced`() {
    val raw = ascii(ElfPatch.LIBCXX, "\u0000", ElfPatch.LIBCXX, "\u0000")

    assertTrue(ElfPatch.replaceCString(raw, ElfPatch.LIBCXX, ElfPatch.LIBCXX_RENAMED))

    val result = text(raw)
    assertEquals(2, result.split(ElfPatch.LIBCXX_RENAMED).size - 1)
    assertFalse(result.contains(ElfPatch.LIBCXX))
    // Каждый слот сохранил исходную длину: 2 × (16 + NUL).
    assertEquals(2 * (ElfPatch.LIBCXX.length + 1), raw.size)
  }

  @Test
  fun `unknown strings stay untouched`() {
    val raw = ascii("libavcodec.so.62", "\u0000libavformat.so.62\u0000")

    assertFalse(ElfPatch.replaceCString(raw, ElfPatch.TERMUX_RUNPATH, ElfPatch.RUNPATH_BINARY))
    assertFalse(ElfPatch.replaceCString(raw, ElfPatch.LIBCXX, ElfPatch.LIBCXX_RENAMED))
    assertEquals("libavcodec.so.62\u0000libavformat.so.62\u0000", text(raw))
  }

  @Test
  fun `patch applies both replacements`() {
    val raw = ascii(
      ElfPatch.TERMUX_RUNPATH,
      "\u0000",
      ElfPatch.LIBCXX,
      "\u0000libavfilter.so.12\u0000",
    )

    ElfPatch.patch(raw, ElfPatch.RUNPATH_LIBRARY)

    val result = text(raw)
    assertTrue(result.contains(ElfPatch.RUNPATH_LIBRARY + "\u0000"))
    assertTrue(result.contains(ElfPatch.LIBCXX_RENAMED + "\u0000"))
    assertTrue(result.contains("libavfilter.so.12\u0000"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `longer replacement is rejected`() {
    // Слот в .dynstr фиксирован: более длинное имя сдвинуло бы таблицу строк.
    ElfPatch.replaceCString(ByteArray(32), "ab", "abcdef")
  }

  @Test
  fun `file name renamed only for libcxx`() {
    assertEquals(ElfPatch.LIBCXX_RENAMED, ElfPatch.fileName(ElfPatch.LIBCXX))
    assertEquals("libavcodec.so.62", ElfPatch.fileName("libavcodec.so.62"))
    assertEquals("libplacebo.so.387", ElfPatch.fileName("libplacebo.so.387"))
  }
}
