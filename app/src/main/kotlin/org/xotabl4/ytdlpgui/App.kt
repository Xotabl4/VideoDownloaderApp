package org.xotabl4.ytdlpgui

import android.app.Application
import org.xotabl4.ytdlpgui.core.AppUpdate
import org.xotabl4.ytdlpgui.core.CrashLog
import org.xotabl4.ytdlpgui.core.DownloadStorage
import org.xotabl4.ytdlpgui.core.DownloadTasks
import org.xotabl4.ytdlpgui.core.SettingsStore
import org.xotabl4.ytdlpgui.core.YtDlp

class App : Application() {
  override fun onCreate() {
    super.onCreate()
    // Перехватчик падений ставим до остальной инициализации, иначе причину сбоя не увидеть.
    CrashLog.install(this)
    // Python и yt-dlp распаковываются в фоне, при первом запуске это около 50 МБ.
    YtDlp.startInit(this)
    // Настройки из SharedPreferences: папка, тема, язык, автообновление.
    DownloadStorage.refresh(this)
    SettingsStore.refresh(this)
    // Список незавершённых загрузок переживает перезапуск приложения.
    DownloadTasks.refresh(this)
    // Проверка новой версии в фоне: не чаще раза в 12 часов и без диалогов.
    AppUpdate.checkOnStart(this)
    // Локаль для строк вне Compose: уведомления и статусы инструментов.
    SettingsStore.applyLanguage(this, SettingsStore.language.value)
  }
}
