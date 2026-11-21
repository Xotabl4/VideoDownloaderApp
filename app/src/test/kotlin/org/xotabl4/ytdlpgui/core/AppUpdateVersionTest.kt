package org.xotabl4.ytdlpgui.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сравнение версий в проверке обновлений. */
class AppUpdateVersionTest {
  @Test
  fun `newer patch version wins`() {
    assertTrue(AppUpdate.isNewer("0.4.0", "0.3.0"))
    assertFalse(AppUpdate.isNewer("0.3.0", "0.3.0"))
    assertFalse(AppUpdate.isNewer("0.2.9", "0.3.0"))
  }

  @Test
  fun `minor version is newer than any patch`() {
    assertTrue(AppUpdate.isNewer("0.10.0", "0.9.9"))
    assertTrue(AppUpdate.isNewer("1.0.0", "0.99.99"))
  }

  @Test
  fun `suffixes and missing parts are ignored`() {
    assertTrue(AppUpdate.isNewer("0.4.0-beta", "0.3.0"))
    assertFalse(AppUpdate.isNewer("0.4", "0.4.0"))
    assertFalse(AppUpdate.isNewer("v1.2.3", "1.2.4"))
  }
}
