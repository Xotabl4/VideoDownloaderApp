package org.xotabl4.ytdlpgui.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.xotabl4.ytdlpgui.MainActivity
import org.xotabl4.ytdlpgui.R
import org.xotabl4.ytdlpgui.core.DownloadManager
import org.xotabl4.ytdlpgui.core.DownloadState

private const val TAG = "DownloadService"
private const val CHANNEL_ID = "downloads"
private const val GROUP_KEY = "org.xotabl4.ytdlpgui.DOWNLOADS"

/** Сводное уведомление foreground-сервиса (само по себе оно и держит сервис живым). */
private const val SUMMARY_ID = 1

/** С какого номера начинаются уведомления конкретных загрузок. */
private const val TASK_ID_BASE = 100
private const val ACTION_CANCEL = "org.xotabl4.ytdlpgui.action.CANCEL_DOWNLOAD"
private const val EXTRA_TASK_ID = "task_id"

/**
 * Foreground service для загрузок (в MVP решено: да).
 *
 * Параллельных загрузок может быть несколько, поэтому сервис показывает **по уведомлению
 * на каждую** активную задачу плюс сводное уведомление (оно и есть foreground). Кнопка
 * «Отмена» в уведомлении несёт `taskId`, так что отменяется именно эта загрузка.
 */
class DownloadService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var wakeLock: PowerManager.WakeLock? = null

  /** id уведомления по taskId — чтобы стабильно обновлять и вовремя убирать. */
  private val notificationIds = mutableMapOf<String, Int>()
  private var foregroundStarted = false

  override fun onCreate() {
    super.onCreate()
    createChannel()
    acquireWakeLock()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_CANCEL) {
      DownloadManager.cancel(intent.getStringExtra(EXTRA_TASK_ID))
      return START_NOT_STICKY
    }

    // Сервис обязан выйти в foreground сразу, иначе система убьёт процесс.
    if (!foregroundStarted) {
      startForeground(SUMMARY_ID, summaryNotification(activeCount = 1, percent = 0))
      foregroundStarted = true
    }

    scope.launch {
      DownloadManager.states.collect { states ->
        val running =
          states.filterValues { it is DownloadState.Running }.mapValues { it.value as DownloadState.Running }
        running.forEach { (taskId, state) ->
          notifyTask(taskId, state)
        }
        // Убираем уведомления задач, которые уже не качаются.
        val finished = notificationIds.keys - running.keys
        finished.forEach { taskId ->
          notificationIds.remove(taskId)?.let { cancelNotification(it) }
        }
        if (running.isNotEmpty()) {
          val percent = running.values.map { (it.percent * 100).toInt() }.average().toInt()
          notifyManager(SUMMARY_ID, summaryNotification(running.size, percent))
        } else if (states.isNotEmpty()) {
          // Всё завершилось — даём секунду прочитать итог и останавливаемся.
          delay(1_500)
          stopSelf()
        }
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    releaseWakeLock()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun createChannel() {
    val manager = getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel =
      NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_downloads), NotificationManager.IMPORTANCE_LOW)
    channel.setShowBadge(false)
    manager.createNotificationChannel(channel)
  }

  /** PendingIntent'ы создаём один раз: уведомление перестраивается на каждом колбэке. */
  private val openApp: PendingIntent by lazy {
    PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  private fun cancelIntent(taskId: String?): PendingIntent =
    PendingIntent.getService(
      this,
      taskId?.hashCode() ?: 0,
      Intent(this, DownloadService::class.java)
        .setAction(ACTION_CANCEL)
        .putExtra(EXTRA_TASK_ID, taskId),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  /** Уведомление конкретной загрузки (одно на задачу). */
  private fun notifyTask(taskId: String, state: DownloadState.Running) {
    val id = notificationIds.getOrPut(taskId) { TASK_ID_BASE + (taskId.hashCode() and 0x3FFF) }
    notifyManager(id, taskNotification(taskId, state))
  }

  private fun taskNotification(taskId: String, state: DownloadState): Notification {
    val running = state is DownloadState.Running
    val percent: Int
    val title: String
    val status: String
    when (state) {
      is DownloadState.Running -> {
        percent = (state.percent * 100).toInt().coerceIn(0, 100)
        title = state.title.ifBlank { getString(R.string.notif_title_fallback) }
        status =
          state.etaSeconds?.let { getString(R.string.notif_progress, percent, formatEta(it)) }
            ?: state.line.take(70)
      }
      is DownloadState.Completed -> {
        percent = 100
        title = state.title.ifBlank { getString(R.string.notif_title_fallback) }
        status = getString(R.string.download_saved_to, state.filePath)
      }
      is DownloadState.Failed -> {
        percent = 0
        title = getString(R.string.notif_title_fallback)
        status = getString(R.string.download_failed, state.message)
      }
      DownloadState.Canceled -> {
        percent = 0
        title = getString(R.string.notif_title_fallback)
        status = getString(R.string.download_canceled)
      }
      DownloadState.Idle -> {
        percent = 0
        title = getString(R.string.notif_title_fallback)
        status = getString(R.string.status_running)
      }
    }

    // Кастомная «плашка» (как в «Загрузках» и на главном): название, полоса-заливка, процент.
    val views = RemoteViews(packageName, R.layout.notification_download)
    views.setTextViewText(R.id.notif_title, title)
    views.setTextViewText(R.id.notif_status, status)
    views.setProgressBar(R.id.notif_progress, 100, percent, false)
    views.setViewVisibility(R.id.notif_cancel, if (running) android.view.View.VISIBLE else android.view.View.GONE)
    if (running) views.setOnClickPendingIntent(R.id.notif_cancel, cancelIntent(taskId))
    views.setOnClickPendingIntent(R.id.notif_title, openApp)
    views.setOnClickPendingIntent(R.id.notif_status, openApp)

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_download)
      .setContentTitle(title)
      .setContentText(status)
      .setContentIntent(openApp)
      .setOnlyAlertOnce(true)
      .setOngoing(running)
      .setShowWhen(false)
      .setCategory(Notification.CATEGORY_PROGRESS)
      .setColor(0xFF7C6CF6.toInt())
      .setGroup(GROUP_KEY)
      .setCustomContentView(views)
      .setCustomBigContentView(views)
      .build()
  }

  /** Сводка по всем параллельным загрузкам: она же foreground-уведомление сервиса. */
  private fun summaryNotification(activeCount: Int, percent: Int): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_download)
      .setContentTitle(getString(R.string.notif_summary_title, activeCount))
      .setContentText(getString(R.string.notif_summary_text, percent))
      .setContentIntent(openApp)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setShowWhen(false)
      .setProgress(100, percent, false)
      .setColor(0xFF7C6CF6.toInt())
      .setGroup(GROUP_KEY)
      .setGroupSummary(true)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .build()

  private fun formatEta(seconds: Long): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return if (minutes > 0) "%d:%02d".format(minutes, rest) else "0:%02d".format(rest)
  }

  private fun notifyManager(id: Int, notification: Notification) {
    val manager = getSystemService(NotificationManager::class.java) ?: return
    try {
      manager.notify(id, notification)
    } catch (e: SecurityException) {
      // POST_NOTIFICATIONS не выдан (API 33+) — не роняем загрузку из-за уведомления.
      Log.w(TAG, "notification not allowed", e)
    }
  }

  private fun cancelNotification(id: Int) {
    runCatching { getSystemService(NotificationManager::class.java)?.cancel(id) }
  }

  private fun acquireWakeLock() {
    val power = getSystemService(PowerManager::class.java) ?: return
    wakeLock =
      power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytdlpgui:download").apply {
        setReferenceCounted(false)
        acquire()
      }
  }

  private fun releaseWakeLock() {
    wakeLock?.takeIf { it.isHeld }?.release()
    wakeLock = null
  }

  companion object {
    fun start(context: Context) {
      ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }
  }
}
