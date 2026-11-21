package org.xotabl4.ytdlpgui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тесты краш-лога. */
class CrashLogTest {
  @Test
  fun `report contains context lines and stack trace`() {
    val error = IllegalStateException("не удалось распаковать", RuntimeException("inner"))

    val report =
      CrashLog.formatReport(
        timestamp = "09.11.2026 12:30:15",
        appVersion = "0.2.2 (5)",
        device = "Android 15 (API 35), Xiaomi 2201116SG",
        threadName = "main",
        error = error,
      )

    assertTrue(report.contains("Time: 09.11.2026 12:30:15"))
    assertTrue(report.contains("App: 0.2.2 (5)"))
    assertTrue(report.contains("Device: Android 15 (API 35), Xiaomi 2201116SG"))
    assertTrue(report.contains("Thread: main"))
    assertTrue(report.contains("java.lang.IllegalStateException: не удалось распаковать"))
    // stackTraceToString печатает и цепочку cause.
    assertTrue(report.contains("Caused by: java.lang.RuntimeException: inner"))
    assertTrue(report.contains("at org.xotabl4.ytdlpgui.core.CrashLogTest"))
  }

  @Test
  fun `file name is prefixed and has txt suffix`() {
    val name = CrashLog.fileName(1_700_000_000_000L)

    assertTrue(name.startsWith("crash-"))
    assertTrue(name.endsWith(".txt"))
    assertTrue(Regex("""^crash-\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.txt$""").matches(name))
  }

  @Test
  fun `file names differ for different seconds`() {
    val first = CrashLog.fileName(1_700_000_000_000L)
    val second = CrashLog.fileName(1_700_000_001_000L)

    assertTrue(first != second)
  }

  @Test
  fun `no deletions while files fit into limit`() {
    val files = listOf("crash-3.txt", "crash-2.txt", "crash-1.txt")

    assertEquals(emptyList<String>(), CrashLog.filesToDelete(files, limit = 3))
  }

  @Test
  fun `oldest files are deleted beyond limit`() {
    val files = (CrashLog.LIMIT + 2 downTo 1).map { "crash-$it.txt" }

    val toDelete = CrashLog.filesToDelete(files)

    assertEquals(listOf("crash-2.txt", "crash-1.txt"), toDelete)
  }

  @Test
  fun `display time has day month hour minute`() {
    assertTrue(Regex("""^\d{2}\.\d{2} \d{2}:\d{2}$""").matches(CrashLog.formatTime(1_700_000_000_000L)))
  }
}
