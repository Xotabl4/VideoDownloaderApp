package org.xotabl4.ytdlpgui

import android.app.Application
import org.xotabl4.ytdlpgui.core.CrashLog
import org.xotabl4.ytdlpgui.core.DownloadStorage
import org.xotabl4.ytdlpgui.core.DownloadTasks
import org.xotabl4.ytdlpgui.core.SettingsStore
import org.xotabl4.ytdlpgui.core.YtDlp

class App : Application() {
  override fun onCreate() {
    super.onCreate()
    // Первым делом: падения записываем в файл (filesDir/crash), иначе причину видно только в logcat.
    CrashLog.install(this)
    // Инициализация Python + yt-dlp асинхронная: первый запуск распаковывает ~50 МБ.
    YtDlp.startInit(this)
    // Восстанавливаем настройки (место сохранения, тема, язык, автообновление yt-dlp).
    DownloadStorage.refresh(this)
    SettingsStore.refresh(this)
    // Незавершённые загрузки (отменённые/упавшие) должны оставаться в «Загрузках» после перезапуска.
    DownloadTasks.refresh(this)
    // Локаль для строк вне Compose (уведомления сервиса, статусы инструментов).
    SettingsStore.applyLanguage(this, SettingsStore.language.value)
  }
}
