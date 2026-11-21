package org.xotabl4.ytdlpgui.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.xotabl4.ytdlpgui.R
import org.xotabl4.ytdlpgui.core.DownloadState

/**
 * Блок прогресса загрузки (десктоп: `CTkProgressBar` + текст + кнопка «Отмена»).
 * Показывает процент, ETA и последнюю строку лога yt-dlp.
 */
@Composable
fun DownloadProgressBlock(
  state: DownloadState,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
  onOpen: (DownloadState.Completed) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
    when (state) {
      DownloadState.Idle -> Unit

      is DownloadState.Running -> {
        val percent = (state.percent * 100).toInt().coerceIn(0, 100)
        DownloadPlate(
          title = state.title.ifBlank { stringResource(R.string.notif_title_fallback) },
          progress = state.percent,
          trailing = "$percent%",
        )
        val eta = state.etaSeconds?.let { " · ETA ${formatEta(it)}" }.orEmpty()
        Text(
          text = "${stringResource(R.string.download_running)} $percent%$eta",
          style = MaterialTheme.typography.bodyMedium,
        )
        if (state.line.isNotEmpty()) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = state.line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.download_cancel)) }
      }

      is DownloadState.Completed -> {
        Text(
          text = stringResource(R.string.download_done, state.title),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          // Для плейлиста строка уже содержит «Плейлист сохранён: …» — префикс «Файл:» лишний.
          text =
            if (state.isPlaylist) {
              state.filePath
            } else {
              stringResource(R.string.download_saved_to, state.filePath)
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { onOpen(state) }) { Text(stringResource(R.string.open_file)) }
      }

      is DownloadState.Failed -> {
        Text(
          text = stringResource(R.string.download_failed, state.message),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row {
          Button(onClick = onRetry) { Text(stringResource(R.string.download_retry)) }
        }
      }

      DownloadState.Canceled -> {
        Text(stringResource(R.string.download_canceled), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.download_retry)) }
      }
    }
  }
}

@Composable
private fun formatEta(seconds: Long): String {
  val minutes = seconds / 60
  val rest = seconds % 60
  return if (minutes > 0) {
    stringResource(R.string.eta_minutes, minutes.toInt(), rest.toInt())
  } else {
    stringResource(R.string.eta_seconds, rest.toInt())
  }
}
