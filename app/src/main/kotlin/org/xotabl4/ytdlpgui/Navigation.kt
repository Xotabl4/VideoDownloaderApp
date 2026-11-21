package org.xotabl4.ytdlpgui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.xotabl4.ytdlpgui.ui.library.LibraryScreen
import org.xotabl4.ytdlpgui.ui.link.LinkScreen
import org.xotabl4.ytdlpgui.ui.settings.SettingsScreen

/** Вкладки нижней навигации — как в концепте: Главная / Загрузки / Настройки. */
enum class MainTab(@StringRes val labelRes: Int, val glyph: String) {
  Home(R.string.nav_home, "⌂"),
  Library(R.string.nav_library, "▤"),
  Settings(R.string.nav_settings, "⚙"),
}

/**
 * Каркас приложения: три экрана в [HorizontalPager] — их можно листать свайпом влево/вправо,
 * нижняя панель синхронизирована с текущей страницей.
 */
@Composable
fun MainNavigation() {
  val pagerState = rememberPagerState(pageCount = { MainTab.entries.size })
  val scope = rememberCoroutineScope()

  Scaffold(
    bottomBar = {
      // Читаем страницу внутри панели, а не в теле MainNavigation: иначе каждый кадр
      // прокрутки пересобирает весь каркас и свайп выглядит дёрганым.
      MainBottomBar(pagerState) { page -> scope.launch { pagerState.animateScrollToPage(page) } }
    },
  ) { innerPadding ->
    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      // Соседняя страница готовится заранее — без этого первый кадр свайпа «подвисает».
      beyondViewportPageCount = 1,
    ) { page ->
      when (MainTab.entries[page]) {
        MainTab.Home ->
          LinkScreen(
            onOpenHistory = { scope.launch { pagerState.animateScrollToPage(MainTab.Library.ordinal) } },
          )
        MainTab.Library -> LibraryScreen()
        MainTab.Settings -> SettingsScreen(onBack = {}, showBack = false)
      }
    }
  }
}

@Composable
private fun MainBottomBar(pagerState: PagerState, onSelect: (Int) -> Unit) {
  NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
    MainTab.entries.forEach { entry ->
      val selected = pagerState.currentPage == entry.ordinal
      NavigationBarItem(
        selected = selected,
        onClick = { onSelect(entry.ordinal) },
        modifier = Modifier.testTag("tab-${entry.name.lowercase()}"),
        icon = { Text(entry.glyph, style = MaterialTheme.typography.titleLarge) },
        label = { Text(stringResource(entry.labelRes)) },
        colors =
          NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
          ),
      )
    }
  }
}
