package org.xotabl4.ytdlpgui.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xotabl4.ytdlpgui.R
import org.xotabl4.ytdlpgui.core.DownloadHistory
import org.xotabl4.ytdlpgui.core.DownloadManager
import org.xotabl4.ytdlpgui.core.DownloadState
import org.xotabl4.ytdlpgui.core.DownloadStorage
import org.xotabl4.ytdlpgui.core.DownloadTask
import org.xotabl4.ytdlpgui.core.DownloadTasks
import org.xotabl4.ytdlpgui.core.FormatsParser
import org.xotabl4.ytdlpgui.core.HistoryEntry
import org.xotabl4.ytdlpgui.core.PlaylistItemProgress
import org.xotabl4.ytdlpgui.core.RunningDownload
import org.xotabl4.ytdlpgui.core.TaskStatus
import org.xotabl4.ytdlpgui.core.ThumbnailCache
import org.xotabl4.ytdlpgui.ui.DownloadPlate
import org.xotabl4.ytdlpgui.ui.rememberNotificationPermissionRequester
import org.xotabl4.ytdlpgui.ui.theme.Emerald
import org.xotabl4.ytdlpgui.ui.theme.SuccessGreen

/** Табы библиотеки. */
private enum class LibraryTab { All, Downloading, Completed }

/** Экран «Загрузки»: табы, карточки, прогресс. */
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  LaunchedEffect(Unit) {
    // Чтение истории из SharedPreferences — в IO, чтобы первый кадр страницы не ждал файл.
    withContext(Dispatchers.IO) { DownloadHistory.load(context) }
  }
  val entries by DownloadHistory.entries.collectAsStateWithLifecycle()
  val tasks by DownloadTasks.entries.collectAsStateWithLifecycle()
  val runningDownloads by DownloadManager.running.collectAsStateWithLifecycle()
  // Повтор из истории тоже запускает загрузку — спрашиваем разрешение на уведомления тогда же.
  val ensureNotifications = rememberNotificationPermissionRequester()
  var tab by rememberSaveable { mutableStateOf(LibraryTab.All) }
  var pendingDelete by remember { mutableStateOf<HistoryEntry?>(null) }
  var pendingTaskDelete by remember { mutableStateOf<DownloadTask?>(null) }
  var clearRequested by remember { mutableStateOf(false) }
  var clearTasksRequested by remember { mutableStateOf(false) }
  // Задача, для которой открыта «шторка» с сериями плейлиста.
  var sheetTaskId by rememberSaveable { mutableStateOf<String?>(null) }

  // Отменённые и упавшие загрузки остаются в списке; идущие показываем отдельными карточками
  // (их может быть несколько — параллельные загрузки).
  val unfinished = tasks.filter { it.status != TaskStatus.Running }
  val visibleHistory =
    when (tab) {
      LibraryTab.All -> entries
      LibraryTab.Downloading -> emptyList()
      LibraryTab.Completed -> entries
    }
  val showTasks = tab != LibraryTab.Completed
  val empty =
    visibleHistory.isEmpty() && runningDownloads.isEmpty() && (!showTasks || unfinished.isEmpty())

  Column(modifier = modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.library_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
      )
      if (entries.isNotEmpty()) {
        TextButton(onClick = { clearRequested = true }) { Text(stringResource(R.string.history_clear)) }
      }
    }
    LibraryTabs(
      current = tab,
      counts =
        mapOf(
          LibraryTab.All to entries.size + unfinished.size + runningDownloads.size,
          LibraryTab.Downloading to unfinished.size + runningDownloads.size,
        ),
      onSelect = { tab = it },
    )
    if (empty) {
      Text(
        text = stringResource(R.string.library_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(20.dp),
      )
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (runningDownloads.isNotEmpty() && tab != LibraryTab.Completed) {
          runningDownloads.forEach { download ->
            item(key = "running-${download.taskId}") {
              RunningCard(
                download = download,
                onCancel = { DownloadManager.cancel(download.taskId) },
                onShowSeries = { sheetTaskId = download.taskId },
              )
            }
          }
        }
        if (showTasks) {
          if (unfinished.isNotEmpty()) {
            item(key = "tasks-clear") {
              Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = stringResource(R.string.tasks_clear_hint, unfinished.size),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clearTasksRequested = true }) {
                  Text(stringResource(R.string.tasks_clear))
                }
              }
            }
          }
          itemsIndexed(unfinished, key = { _, task -> task.id }) { _, task ->
            TaskCard(
              task = task,
              onRetry = { ensureNotifications { LibraryActions.retryTask(context, task) } },
              onDelete = { pendingTaskDelete = task },
            )
          }
        }
        itemsIndexed(visibleHistory, key = { _, entry -> entry.id }) { index, entry ->
          LibraryCard(
            index = index + 1,
            entry = entry,
            onOpen = { LibraryActions.open(context, entry) },
            onRetry = {
              ensureNotifications { LibraryActions.retry(context, entry) }
            },
            onDelete = { pendingDelete = entry },
          )
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
      }
    }
  }

  pendingTaskDelete?.let { task ->
    AlertDialog(
      onDismissRequest = { pendingTaskDelete = null },
      title = { Text(stringResource(R.string.history_delete)) },
      text = { Text(stringResource(R.string.task_delete_question, task.title)) },
      confirmButton = {
        TextButton(
          onClick = {
            pendingTaskDelete = null
            DownloadTasks.remove(context, task.id)
            // Удаляем недокачанные части именно этой задачи, иначе место не освобождается.
            DownloadStorage.clearTemp(context, task.id)
          }
        ) {
          Text(stringResource(R.string.dialog_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingTaskDelete = null }) { Text(stringResource(R.string.dialog_cancel)) }
      },
    )
  }

  pendingDelete?.let { entry ->
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text(stringResource(R.string.history_delete)) },
      text = { Text(stringResource(R.string.history_delete_question, entry.title)) },
      confirmButton = {
        TextButton(
          onClick = {
            pendingDelete = null
            scope.launch { DownloadHistory.remove(context, entry.id, deleteFile = true) }
          }
        ) {
          Text(stringResource(R.string.dialog_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.dialog_cancel)) }
      },
    )
  }

  if (clearTasksRequested) {
    AlertDialog(
      onDismissRequest = { clearTasksRequested = false },
      title = { Text(stringResource(R.string.tasks_clear)) },
      text = { Text(stringResource(R.string.tasks_clear_question)) },
      confirmButton = {
        TextButton(
          onClick = {
            clearTasksRequested = false
            // Отменённые и упавшие задачи могли оставить недокачанные части в кэше.
            DownloadTasks.clearFinished(context).forEach { DownloadStorage.clearTemp(context, it) }
          },
        ) {
          Text(stringResource(R.string.tasks_clear))
        }
      },
      dismissButton = {
        TextButton(onClick = { clearTasksRequested = false }) { Text(stringResource(R.string.dialog_cancel)) }
      },
    )
  }

  if (clearRequested) {
    AlertDialog(
      onDismissRequest = { clearRequested = false },
      title = { Text(stringResource(R.string.history_clear)) },
      text = { Text(stringResource(R.string.history_clear_question)) },
      confirmButton = {
        TextButton(
          onClick = {
            clearRequested = false
            scope.launch { DownloadHistory.clear(context, deleteFiles = true) }
          }
        ) {
          Text(stringResource(R.string.dialog_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { clearRequested = false }) { Text(stringResource(R.string.dialog_cancel)) }
      },
    )
  }

  // «Шторка» с сериями плейлиста: показываем, пока задача качается.
  val sheetDownload = sheetTaskId?.let { id -> runningDownloads.firstOrNull { it.taskId == id } }
  if (sheetDownload != null) {
    PlaylistSheet(download = sheetDownload, onDismiss = { sheetTaskId = null })
  }
}

/** Табы библиотеки: подпись и подчёркивание у выбранного. */
@Composable
private fun LibraryTabs(current: LibraryTab, counts: Map<LibraryTab, Int>, onSelect: (LibraryTab) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    LibraryTab.entries.forEach { entry ->
      val selected = entry == current
      val label =
        when (entry) {
          LibraryTab.All -> stringResource(R.string.library_tab_all)
          LibraryTab.Downloading -> stringResource(R.string.library_tab_downloading)
          LibraryTab.Completed -> stringResource(R.string.library_tab_completed)
        }
      val count = counts[entry] ?: 0
      Column(
        modifier = Modifier.weight(1f).clickable { onSelect(entry) },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = if (count > 0) "$label $count" else label,
          style = MaterialTheme.typography.titleSmall,
          color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .height(2.dp)
              .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
        )
      }
    }
  }
  Spacer(modifier = Modifier.height(10.dp))
}

/**
 * Карточка активной загрузки: «плашка» с прогрессом (стиль RimWorld-мода) + отмена.
 *
 * У плейлиста плашка показывает общий прогресс по всем сериям, ниже — какая серия идёт,
 * и кнопка «Серии», открывающая «шторку» с полосками каждой серии.
 */
@Composable
private fun RunningCard(download: RunningDownload, onCancel: () -> Unit, onShowSeries: () -> Unit) {
  val progress = download.percent / 100f
  val items = download.playlistItems
  val total = download.itemCount.coerceAtLeast(items.size)
  val isPlaylist = download.isPlaylist && (items.isNotEmpty() || total > 1)
  val current = items.firstOrNull { it.index == download.itemIndex }

  Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
    Column(modifier = Modifier.padding(12.dp)) {
      DownloadPlate(
        title =
          if (isPlaylist) {
            download.playlistTitle.ifBlank { stringResource(R.string.notif_title_fallback) }
          } else {
            download.title.ifBlank { stringResource(R.string.notif_title_fallback) }
          },
        progress = progress,
        trailing = "${download.percent}%",
      )
      if (isPlaylist) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          val index = download.itemIndex
          Text(
            text =
              when {
                index != null && total > 0 -> stringResource(R.string.playlist_series_of, index, total)
                index != null -> stringResource(R.string.playlist_item_number, index)
                total > 0 -> stringResource(R.string.playlist_preparing, total)
                else -> stringResource(R.string.playlist_show_series_unknown)
              },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = SuccessGreen,
          )
          val currentTitle = current?.title.orEmpty()
          Text(
            text = if (currentTitle.isBlank()) "" else " · $currentTitle",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          TextButton(
            onClick = onShowSeries,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
          ) {
            Text(
              text =
                if (total > 0) {
                  stringResource(R.string.playlist_show_series, total)
                } else {
                  stringResource(R.string.playlist_show_series_unknown)
                },
              style = MaterialTheme.typography.labelMedium,
            )
          }
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = download.line.take(60),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text(stringResource(R.string.download_cancel)) }
      }
    }
  }
}

/** «Шторка» с сериями плейлиста: номер, название и полоска каждой серии. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistSheet(download: RunningDownload, onDismiss: () -> Unit) {
  val items = download.playlistItems
  val total = download.itemCount.coerceAtLeast(items.size)
  val done = items.count { it.done }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
      Text(
        text = download.playlistTitle.ifBlank { stringResource(R.string.playlist_sheet_title) },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = stringResource(R.string.playlist_sheet_summary, done, total, download.percent),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(12.dp))
      LazyColumn(
        modifier = Modifier.heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(items, key = { it.index }) { item -> SeriesRow(item) }
      }
    }
  }
}

/** Строка серии в «шторке»: номер, название, полоска и процент. */
@Composable
private fun SeriesRow(item: PlaylistItemProgress) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = if (item.index < 10) "0${item.index}" else "${item.index}",
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = if (item.done) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(26.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = item.title.ifBlank { stringResource(R.string.playlist_item_number, item.index) },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(4.dp))
      SeriesBar(progress = item.percent / 100f, done = item.done)
    }
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = if (item.done) "✓" else if (item.percent > 0) "${item.percent}%" else "",
      style = MaterialTheme.typography.labelSmall,
      color = if (item.done) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.End,
      modifier = Modifier.width(36.dp),
    )
  }
}

/** Маленькая полоска серии: та же зелёная заливка, что у большой плашки. */
@Composable
private fun SeriesBar(progress: Float, done: Boolean) {
  val fraction = progress.coerceIn(0f, 1f)
  Box(
    modifier =
      Modifier.fillMaxWidth()
        .height(6.dp)
        .clip(RoundedCornerShape(3.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
  ) {
    Box(
      modifier =
        Modifier.fillMaxWidth(fraction)
          .fillMaxHeight()
          .background(
            if (done) {
              Brush.linearGradient(listOf(SuccessGreen.copy(alpha = 0.7f), SuccessGreen))
            } else {
              Brush.linearGradient(listOf(Emerald.copy(alpha = 0.45f), Emerald.copy(alpha = 0.95f)))
            }
          )
    )
  }
}



/** Карточка незавершённой загрузки: отменённая или упавшая — остаётся в списке. */
@Composable
private fun TaskCard(task: DownloadTask, onRetry: () -> Unit, onDelete: () -> Unit) {
  val context = LocalContext.current
  Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = task.title.ifBlank { stringResource(R.string.notif_title_fallback) },
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = "${task.percent}%",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text =
          if (task.status == TaskStatus.Failed && task.message.isNotBlank()) {
            "${DownloadTasks.statusLabel(context, task.status)}: ${task.message}"
          } else {
            DownloadTasks.statusLabel(context, task.status)
          },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onRetry) { Text(stringResource(R.string.history_retry)) }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.history_delete)) }
      }
    }
  }
}

/** Карточка записи: превью с таймкодом, номер, название, размер, статус. */
@Composable
private fun LibraryCard(
  index: Int,
  entry: HistoryEntry,
  onOpen: () -> Unit,
  onRetry: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onOpen)) {
    Row(
      modifier = Modifier.padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Thumbnail(entry = entry, modifier = Modifier.width(96.dp).aspectRatio(16f / 10f))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "$index. ${stringResource(R.string.library_video)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = entry.title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = stringResource(R.string.library_size, FormatsParser.sizeLabel(entry.sizeBytes)),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = stringResource(R.string.library_progress_completed),
          style = MaterialTheme.typography.bodySmall,
          color = SuccessGreen,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          TextButton(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
            Text(stringResource(R.string.history_retry), style = MaterialTheme.typography.labelMedium)
          }
          TextButton(onClick = onDelete, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
            Text(stringResource(R.string.history_delete), style = MaterialTheme.typography.labelMedium)
          }
        }
      }
      Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Text(text = "✓", color = SuccessGreen, style = MaterialTheme.typography.titleMedium)
      }
    }
  }
}

/** Миниатюра из кэша (качается один раз) с таймкодом в углу. */
@Composable
private fun Thumbnail(entry: HistoryEntry, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val bitmap by
    produceState<androidx.compose.ui.graphics.ImageBitmap?>(
      initialValue = ThumbnailCache.peek(entry.thumbnail)?.asImageBitmap(),
      entry.thumbnail,
    ) {
      if (value == null) value = ThumbnailCache.load(context, entry.thumbnail)?.asImageBitmap()
    }
  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(10.dp))
        .background(
          Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface))
        )
  ) {
    if (bitmap != null) {
      Image(
        bitmap = bitmap!!,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.matchParentSize(),
      )
    } else {
      Text(
        text = "▶",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.Center),
      )
    }
    val duration = FormatsParser.formatDuration(entry.durationSeconds)
    if (duration.isNotEmpty()) {
      Text(
        text = duration,
        style = MaterialTheme.typography.labelSmall,
        color = androidx.compose.ui.graphics.Color.White,
        modifier =
          Modifier.align(Alignment.BottomStart)
            .padding(4.dp)
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
      )
    }
  }
}

/** Открытие/повтор — общая логика для карточки библиотеки. */
private object LibraryActions {
  fun open(context: android.content.Context, entry: HistoryEntry) {
    val intent =
      android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(android.net.Uri.parse(entry.uri), entry.mimeType)
        addFlags(
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        )
      }
    runCatching { context.startActivity(intent) }
  }

  fun retry(context: android.content.Context, entry: HistoryEntry) {
    if (entry.url.isBlank()) return
    org.xotabl4.ytdlpgui.service.DownloadService.start(context)
    DownloadManager.start(
      context,
      entry.url,
      entry.formatId,
      entry.conversion.takeIf { it.isNotBlank() },
      thumbnail = entry.thumbnail,
      durationSeconds = entry.durationSeconds,
      title = entry.title,
      playlistTitle = entry.playlistTitle,
      isPlaylist = entry.isPlaylist,
    )
  }

  /** Повтор незавершённой загрузки: старая запись удаляется, новая создаётся в [DownloadTasks]. */
  fun retryTask(context: android.content.Context, task: DownloadTask) {
    if (task.url.isBlank()) return
    DownloadTasks.remove(context, task.id)
    org.xotabl4.ytdlpgui.service.DownloadService.start(context)
    DownloadManager.start(
      context,
      task.url,
      task.formatId,
      task.conversion.takeIf { it.isNotBlank() },
      thumbnail = task.thumbnail,
      durationSeconds = task.durationSeconds,
      title = task.title,
      playlistTitle = task.playlistTitle,
      isPlaylist = task.isPlaylist,
      playlistCount = task.playlistCount,
    )
  }
}
