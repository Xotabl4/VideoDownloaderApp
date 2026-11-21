package org.xotabl4.ytdlpgui

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.xotabl4.ytdlpgui.core.SettingsStore

/**
 * UI-тесты навигации.
 *
 * Activity запускается через am start: MIUI блокирует запуск из теста.
 */
class MainNavigationTest {

  @get:Rule val rule = createEmptyComposeRule()

  private val context: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun setUp() {
    // В @Before нельзя звать SettingsStore.setLanguage(): updateConfiguration во время
    // старта Activity вешает main thread. Пишем префы напрямую и обновляем StateFlow.
    writePrefs(language = "ru", theme = "dark")
    rule.mainClock.autoAdvance = false
    startApp()
    waitForTag("link-input")
  }

  @After
  fun tearDown() {
    rule.mainClock.autoAdvance = true
    writePrefs(language = "ru", theme = "dark")
    SettingsStore.setAutoUpdateYtDlp(context, false)
  }

  private fun writePrefs(language: String, theme: String) {
    context
      .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
      .edit()
      .putString("language", language)
      .putString("theme", theme)
      .commit()
    SettingsStore.refresh(context)
  }

  /** Запуск приложения через shell: обходит запрет фонового старта Activity в MIUI. */
  private fun startApp() {
    val fd =
      InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("am start -n org.xotabl4.ytdlpgui/.MainActivity")
    ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
  }

  private fun waitForTag(tag: String, timeoutMillis: Long = 12_000) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
      val found =
        runCatching { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
      if (found) return
      rule.mainClock.advanceTimeBy(200)
      Thread.sleep(50)
    }
    fail("Не дождались элемента «$tag» за $timeoutMillis мс")
  }

  /** Ждёт, пока элемент действительно окажется на экране (а не просто в скомпонованной соседней странице). */
  private fun waitForDisplayed(text: String, timeoutMillis: Long = 8_000) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
      val displayed =
        runCatching {
            rule.onNodeWithText(text).assertIsDisplayed()
            true
          }
          .getOrDefault(false)
      if (displayed) return
      rule.mainClock.advanceTimeBy(100)
      Thread.sleep(30)
    }
    fail("Элемент «$text» так и не появился на экране за $timeoutMillis мс")
  }

  private fun goToTab(tag: String, expectedText: String) {
    rule.onNodeWithTag(tag).performClick()
    // Пейджер доезжает анимацией: сначала прокручиваем кадры, затем ждём видимость.
    repeat(15) {
      rule.mainClock.advanceTimeBy(50)
      Thread.sleep(10)
    }
    waitForDisplayed(expectedText)
  }

  @Test
  fun homeShowsLinkPillAndHint() {
    goToTab("tab-home", "Укажите ссылку на видео в поле выше")
    rule.onNodeWithTag("link-input").assertIsDisplayed()
    rule.onNodeWithText("Получить форматы").assertIsDisplayed()
  }

  @Test
  fun typingLinkDoesNotFetchFormatsAutomatically() {
    goToTab("tab-home", "Укажите ссылку на видео в поле выше")
    rule.onNodeWithTag("link-input").performTextInput("https://example.com/video")

    // Автозапрос раньше срабатывал через 0,7 с после ввода — ждём с запасом.
    Thread.sleep(1_500)
    rule.mainClock.advanceTimeBy(500)

    rule.onNodeWithText("Получаю форматы…").assertDoesNotExist()
    rule.onNodeWithText("Укажите ссылку на видео в поле выше").assertIsDisplayed()
  }

  @Test
  fun bottomBarOpensLibraryAndSettings() {
    goToTab("tab-library", "Все")
    goToTab("tab-settings", "Оформление")
    rule.onNodeWithText("Лицензия: GPL-3.0").assertExists()
  }

  @Test
  fun languageSwitchTranslatesUiImmediately() {
    goToTab("tab-settings", "Оформление")

    rule.onNodeWithText("English").performScrollTo().performClick()
    waitForDisplayed("Appearance")
    rule.onNodeWithText("Dark theme").assertExists()

    rule.onNodeWithText("Русский").performScrollTo().performClick()
    waitForDisplayed("Оформление")
  }

  @Test
  fun themeSwitchTogglesBothWays() {
    goToTab("tab-settings", "Оформление")

    // На экране настроек ровно один переключатель — тема.
    rule.onNode(isToggleable()).performScrollTo().performClick()
    rule.mainClock.advanceTimeBy(500)
    rule.onNodeWithText("Оформление").assertExists()

    rule.onNode(isToggleable()).performScrollTo().performClick()
    rule.mainClock.advanceTimeBy(500)
    rule.onNodeWithText("Оформление").assertExists()
  }
}
