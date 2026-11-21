package org.xotabl4.ytdlpgui.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import org.xotabl4.ytdlpgui.R

/** Запрашивает уведомления при запуске загрузки, а не на старте приложения. */
@Composable
fun rememberNotificationPermissionRequester(): (onReady: () -> Unit) -> Unit {
  val context = LocalContext.current
  // Действие, ожидающее ответа пользователя (наш диалог + системный запрос).
  var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
  var showRationale by remember { mutableStateOf(false) }

  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      val action = pendingAction
      pendingAction = null
      action?.invoke()
    }

  val missingPermission =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

  val startDownload: () -> Unit = {
    val action = pendingAction
    pendingAction = null
    action?.invoke()
  }

  if (showRationale && missingPermission) {
    AlertDialog(
      onDismissRequest = {
        showRationale = false
        startDownload()
      },
      title = { Text(stringResource(R.string.notif_permission_title)) },
      text = { Text(stringResource(R.string.notif_permission_text)) },
      confirmButton = {
        TextButton(
          onClick = {
            showRationale = false
            runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
              .onFailure { startDownload() }
          }
        ) {
          Text(stringResource(R.string.notif_permission_allow))
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            showRationale = false
            startDownload()
          }
        ) {
          Text(stringResource(R.string.notif_permission_later))
        }
      },
    )
  }

  // Лямбду не запоминаем: иначе она держит старое значение `missingPermission`
  // (например, «разрешения нет») и после выдачи разрешения кнопка «Скачать» молчит.
  return { onReady ->
    when {
      missingPermission -> {
        pendingAction = onReady
        showRationale = true
      }
      else -> onReady()
    }
  }
}
