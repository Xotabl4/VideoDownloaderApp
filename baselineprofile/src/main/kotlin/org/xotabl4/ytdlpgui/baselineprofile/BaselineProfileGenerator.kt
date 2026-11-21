package org.xotabl4.ytdlpgui.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "org.xotabl4.ytdlpgui"

/**
 * Генератор Baseline Profile.
 *
 * Запуск: `gradlew.bat :app:generateReleaseBaselineProfile`. Профиль снимается на подключённом
 * устройстве и попадает в релизную сборку через androidx.profileinstaller.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

  @get:Rule val rule = BaselineProfileRule()

  /**
   * Один сценарий на всё: холодный старт + переходы по вкладкам.
   *
   * Разделять на два теста нельзя: macrobenchmark переустанавливает APK перед каждым тестом,
   * а MIUI каждый раз требует ручного подтверждения установки — прогон обрывается.
   */
  @Test
  fun startupAndNavigation() =
    rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
      pressHome()
      startActivityAndWait()
      // Ждём, пока появится окно приложения, и даём Compose дорисовать первый кадр.
      device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 5_000)
      Thread.sleep(1_500)

      // Нижняя панель: Главная → Загрузки → Настройки → Главная.
      val width = device.displayWidth
      val height = device.displayHeight
      val y = (height * 0.9f).toInt()
      device.click(width / 6, y) // Главная
      Thread.sleep(1_000)
      device.click(width / 2, y) // Загрузки
      Thread.sleep(1_000)
      device.click(width * 5 / 6, y) // Настройки
      Thread.sleep(1_500)
      device.click(width / 6, y) // назад на Главную
      Thread.sleep(1_000)
    }
}
