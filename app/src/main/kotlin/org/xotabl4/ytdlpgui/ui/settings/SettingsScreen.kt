package org.xotabl4.ytdlpgui.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.xotabl4.ytdlpgui.R
import org.xotabl4.ytdlpgui.core.AppLanguage
import org.xotabl4.ytdlpgui.core.AppTheme
import org.xotabl4.ytdlpgui.core.CrashLog
import org.xotabl4.ytdlpgui.core.AppUpdate
import org.xotabl4.ytdlpgui.core.DownloadStorage
import org.xotabl4.ytdlpgui.core.Links
import org.xotabl4.ytdlpgui.core.PARALLEL_DOWNLOAD_OPTIONS
import org.xotabl4.ytdlpgui.core.SettingsStore
import org.xotabl4.ytdlpgui.core.ToolStatus
import org.xotabl4.ytdlpgui.core.UpdateDownloadState
import org.xotabl4.ytdlpgui.core.UpdateInstaller
import org.xotabl4.ytdlpgui.core.UpdateState
import org.xotabl4.ytdlpgui.core.YtDlp
import org.xotabl4.ytdlpgui.core.YtDlpState
import org.xotabl4.ytdlpgui.ui.theme.SuccessGreen
import org.xotabl4.ytdlpgui.ui.theme.paletteColors

/** Экран настроек: карточки-секции с параметрами и инструментами. */
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  showBack: Boolean = true,
) {
  val context = LocalContext.current
  val ytDlpState by YtDlp.state.collectAsStateWithLifecycle()
  val ytDlpStatus by YtDlp.ytdlpStatus.collectAsStateWithLifecycle()
  val ffmpegStatus by YtDlp.ffmpegStatus.collectAsStateWithLifecycle()
  val youtubeStatus by YtDlp.youtubeStatus.collectAsStateWithLifecycle()
  val destination by DownloadStorage.destination.collectAsStateWithLifecycle()
  val theme by SettingsStore.theme.collectAsStateWithLifecycle()
  val language by SettingsStore.language.collectAsStateWithLifecycle()
  val updateState by AppUpdate.state.collectAsStateWithLifecycle()
  val downloadState by UpdateInstaller.state.collectAsStateWithLifecycle()

  // Разрешение на работу без оптимизации батареи пересчитывается при возврате в приложение:
  // его могли включить в системных настройках.
  val lifecycleOwner = LocalLifecycleOwner.current
  var ignoringBattery by remember(context) { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
  DisposableEffect(lifecycleOwner, context) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) ignoringBattery = isIgnoringBatteryOptimizations(context)
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val versions = ytDlpState as? YtDlpState.Ready
  // Чтение version.txt из assets — на каждую рекомпозицию это I/O в UI-потоке (рывки при свайпе).
  val ffmpegReference = remember(context) { YtDlp.ffmpegReferenceVersion(context) }
  val appVersion =
    remember(context) {
      runCatching {
          context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }
        .getOrNull() ?: "—"
    }
  // Отчёт о последнем падении: показываем, только если он есть (иначе в «О программе» пусто).
  var crashFile by remember(context) { mutableStateOf(CrashLog.lastCrash(context)) }

  // Подпись «выбранная папка» — из ресурсов: строка попадает в настройки и должна быть на языке UI.
  val fallbackFolderLabel = stringResource(R.string.dest_picked_folder)
  val folderPicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
      if (treeUri != null) {
        runCatching {
          context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )
        }
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")
        val label = treeId.substringAfter(':', treeId).ifBlank { fallbackFolderLabel }
        DownloadStorage.setSafTree(context, treeUri, label)
      }
    }

  Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.settings_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
      )
      if (showBack) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
      }
    }

    SettingsCard(title = stringResource(R.string.settings_downloads)) {
      Text(
        text = stringResource(R.string.dest_label, destination.label),
        style = MaterialTheme.typography.bodyMedium,
      )
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.dest_pick), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(
          onClick = { DownloadStorage.resetToDefault(context) },
          modifier = Modifier.weight(1f),
        ) {
          Text(stringResource(R.string.dest_reset), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }

     SettingsCard(title = stringResource(R.string.settings_parallel)) {
      val parallel by SettingsStore.parallelDownloads.collectAsStateWithLifecycle()
      Text(
        text = stringResource(R.string.settings_parallel_value, parallel),
        style = MaterialTheme.typography.bodyMedium,
      )
      StepSlider(
        options = PARALLEL_DOWNLOAD_OPTIONS,
        valueIndex = PARALLEL_DOWNLOAD_OPTIONS.indexOf(parallel).coerceAtLeast(0),
        onSelect = { SettingsStore.setParallelDownloads(context, it) },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      )
      // Подписи трёх положений: 2 / 5 / 10.
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        PARALLEL_DOWNLOAD_OPTIONS.forEach { option ->
          Text(
            text = option.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Text(
        text = stringResource(R.string.settings_parallel_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    SettingsCard(title = stringResource(R.string.settings_appearance)) {
      Text(
        text = stringResource(R.string.settings_theme_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(10.dp))
      // Палитры в два столбца: цветное превью + название + описание.
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTheme.entries.chunked(2).forEach { rowThemes ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            rowThemes.forEach { palette ->
              PaletteButton(
                palette = palette,
                selected = palette == theme,
                onClick = { SettingsStore.setTheme(context, palette) },
                modifier = Modifier.weight(1f),
              )
            }
            if (rowThemes.size == 1) Spacer(modifier = Modifier.weight(1f))
          }
        }
      }
      Spacer(modifier = Modifier.height(12.dp))
      Text(text = stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyMedium)
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilterChip(
          selected = language == AppLanguage.Ru,
          onClick = { SettingsStore.setLanguage(context, AppLanguage.Ru) },
          label = { Text(stringResource(R.string.language_ru)) },
        )
        FilterChip(
          selected = language == AppLanguage.En,
          onClick = { SettingsStore.setLanguage(context, AppLanguage.En) },
          label = { Text(stringResource(R.string.language_en)) },
        )
      }
    }

    SettingsCard(title = stringResource(R.string.settings_battery)) {
      if (ignoringBattery) {
        Text(
          text = stringResource(R.string.battery_status_ok),
          style = MaterialTheme.typography.bodySmall,
          color = SuccessGreen,
        )
      } else {
        Text(
          text = stringResource(R.string.battery_optimization_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
          onClick = { requestIgnoreBatteryOptimizations(context) },
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
          Text(stringResource(R.string.battery_optimization_off))
        }
      }
    }

    SettingsCard(title = stringResource(R.string.settings_tools)) {
      ToolBlock(
        title = "yt-dlp",
        version = versions?.ytDlpVersion,
        referenceVersion = YtDlp.bundledYtDlpVersion,
        status = ytDlpStatus,
        actions =
          listOf(
            stringResource(R.string.settings_check) to { YtDlp.checkYtDlp(context) },
            stringResource(R.string.settings_update) to { YtDlp.updateYtDlp(context) },
            stringResource(R.string.settings_reinstall) to { YtDlp.reinstallYtDlp(context) },
          ),
        onInstallBundled = { YtDlp.reinstallYtDlp(context) },
      )
      Spacer(modifier = Modifier.height(14.dp))
      ToolBlock(
        title = "ffmpeg",
        version = versions?.ffmpegVersion,
        referenceVersion = ffmpegReference,
        status = ffmpegStatus,
        actions =
          listOf(
            stringResource(R.string.settings_check) to { YtDlp.checkFfmpeg(context) },
            stringResource(R.string.settings_update) to { YtDlp.updateFfmpeg(context) },
            stringResource(R.string.settings_reinstall) to { YtDlp.reinstallFfmpeg(context) },
          ),
        onInstallBundled = { YtDlp.reinstallFfmpeg(context) },
      )
    }

    SettingsCard(title = stringResource(R.string.settings_network)) {
      OutlinedButton(onClick = { YtDlp.checkYouTube(context) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_check_youtube))
      }
      StatusText(youtubeStatus)
    }

    SettingsCard(title = stringResource(R.string.settings_about)) {
      Text(
        text = stringResource(R.string.about_app_version, appVersion),
        style = MaterialTheme.typography.bodyMedium,
      )
      UpdateSection(
        updateState = updateState,
        downloadState = downloadState,
        onCheck = { AppUpdate.check(context) },
        onDownload = { url -> UpdateInstaller.download(context, url) },
        onOpen = { url -> openUrl(context, url) },
        onInstall = { file ->
          UpdateInstaller.reset()
          runCatching { context.startActivity(UpdateInstaller.installIntent(context, file)) }
            .onFailure { Log.w("Settings", "install update: ${it.message}") }
        },
      )
      Text(
        text = stringResource(R.string.about_license),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp),
      )
      Text(
        text = stringResource(R.string.about_license_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { openUrl(context, Links.REPOSITORY) }) {
          Text(stringResource(R.string.about_source), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { shareApp(context) }) {
          Text(stringResource(R.string.about_share_app), style = MaterialTheme.typography.bodyMedium)
        }
      }
      // Отчёт о последнем падении: отправка через «Поделиться» (FileProvider) и удаление.
      crashFile?.let { file ->
        Text(
          text = stringResource(R.string.about_crash_log, CrashLog.formatTime(file.lastModified())),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          val buttonPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
          TextButton(
            onClick = { context.startActivity(CrashLog.shareIntent(context, file)) },
            contentPadding = buttonPadding,
          ) {
            Text(stringResource(R.string.about_crash_share), style = MaterialTheme.typography.bodyMedium)
          }
          TextButton(
            onClick = {
              file.delete()
              crashFile = CrashLog.lastCrash(context)
            },
            contentPadding = buttonPadding,
          ) {
            Text(stringResource(R.string.about_crash_delete), style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
    }
    Spacer(modifier = Modifier.height(16.dp))
  }
}

/** Кнопка палитры: цветное превью, название, описание и подсветка выбранной. */
@Composable
private fun PaletteButton(
  palette: AppTheme,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = paletteColors(palette)
  val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
  val borderWidth = if (selected) 2.dp else 1.dp

  Column(
    modifier =
      modifier
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .padding(10.dp),
  ) {
    Box(modifier = Modifier.size(36.dp)) {
      // Фон палитры с тонкой рамкой, поверх — акцент.
      Box(
        Modifier.size(36.dp).clip(CircleShape).background(colors.background)
          .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
      )
      Box(
        Modifier.align(Alignment.Center).size(20.dp).clip(CircleShape).background(colors.primary)
      )
      if (selected) {
        Box(
          Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "✓",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = stringResource(palette.labelRes),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = stringResource(palette.descriptionRes),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** Ползунок на несколько положений: сплошная полоса и шарик, который тянет заливку. */
@Composable
private fun StepSlider(
  options: List<Int>,
  valueIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val thumbDiameter = 24.dp
  val trackHeight = 6.dp
  val activeColor = MaterialTheme.colorScheme.primary
  val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

  val maxIndex = (options.size - 1).coerceAtLeast(0)
  var widthPx by remember { mutableIntStateOf(0) }

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxWidth()
        .height(thumbDiameter)
        .onSizeChanged { widthPx = it.width }
        .pointerInput(maxIndex, options.size) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fun selectAt(x: Float) {
              val w = widthPx
              if (w <= 0 || maxIndex == 0) return
              val index = (x / w * maxIndex).roundToInt().coerceIn(0, maxIndex)
              onSelect(options[index])
            }
            selectAt(down.position.x)
            down.consume()
            drag(down.id) { change ->
              selectAt(change.position.x)
              change.consume()
            }
          }
        },
    contentAlignment = Alignment.CenterStart,
  ) {
    val fraction = if (maxIndex == 0) 0f else valueIndex.toFloat() / maxIndex

    // Полоса целиком — неактивная часть.
    Box(
      Modifier
        .fillMaxWidth()
        .height(trackHeight)
        .clip(RoundedCornerShape(trackHeight / 2))
        .background(inactiveColor)
    )
    // Заливка активной части.
    if (fraction > 0f) {
      Box(
        Modifier
          .fillMaxWidth(fraction)
          .height(trackHeight)
          .clip(RoundedCornerShape(trackHeight / 2))
          .background(activeColor)
      )
    }
    // Шарик на границе заливки.
    Box(
      modifier =
        Modifier.offset(x = (maxWidth - thumbDiameter) * fraction)
          .size(thumbDiameter)
          .clip(CircleShape)
          .background(activeColor),
      contentAlignment = Alignment.Center,
    ) {}
  }
}


/** Пакет уже в «белом списке» оптимизации батареи? */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
  context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

/** Системный диалог «Разрешить работу в фоне без оптимизации батареи». */
private fun requestIgnoreBatteryOptimizations(context: Context) {
  val intent =
    Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
      )
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  runCatching { context.startActivity(intent) }.onFailure { Log.w("Settings", "battery intent: ${it.message}") }
}

/** Проверка обновлений: статус, скачивание APK с прогрессом и запуск установщика. */
@Composable
private fun UpdateSection(
  updateState: UpdateState,
  downloadState: UpdateDownloadState,
  onCheck: () -> Unit,
  onDownload: (String) -> Unit,
  onOpen: (String) -> Unit,
  onInstall: (File) -> Unit,
) {
  val statusText =
    when (updateState) {
      UpdateState.Idle -> stringResource(R.string.about_update_idle)
      UpdateState.Checking -> stringResource(R.string.about_update_checking)
      UpdateState.UpToDate -> stringResource(R.string.about_update_latest)
      UpdateState.NoReleases -> stringResource(R.string.about_update_none)
      is UpdateState.Available -> stringResource(R.string.about_update_available, updateState.version)
      is UpdateState.Failed -> stringResource(R.string.about_update_failed, updateState.message)
    }
  Text(
    text = statusText,
    style = MaterialTheme.typography.bodySmall,
    color =
      if (updateState is UpdateState.Available) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 8.dp),
  )

  when (val dl = downloadState) {
    is UpdateDownloadState.Downloading -> {
      LinearProgressIndicator(
        progress = { dl.percent / 100f },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      )
      Text(
        text =
          if (dl.percent > 0) stringResource(R.string.about_update_downloading, dl.percent)
          else stringResource(R.string.about_update_downloading_no_size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    is UpdateDownloadState.Ready ->
      Text(
        text = stringResource(R.string.about_update_ready),
        style = MaterialTheme.typography.bodySmall,
        color = SuccessGreen,
        modifier = Modifier.padding(top = 8.dp),
      )
    is UpdateDownloadState.Failed ->
      Text(
        text = stringResource(R.string.about_update_download_failed, dl.message),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp),
      )
    UpdateDownloadState.Idle -> Unit
  }

  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    TextButton(
      onClick = onCheck,
      enabled = updateState != UpdateState.Checking && downloadState !is UpdateDownloadState.Downloading,
    ) {
      Text(stringResource(R.string.about_update_check))
    }
    val available = updateState as? UpdateState.Available
    if (available != null &&
      downloadState !is UpdateDownloadState.Downloading &&
      downloadState !is UpdateDownloadState.Ready
    ) {
      TextButton(onClick = { available.apkUrl?.let(onDownload) ?: onOpen(available.pageUrl) }) {
        Text(stringResource(R.string.about_update_open))
      }
    }
  }

  // Как только APK скачан — открываем системный установщик.
  LaunchedEffect(downloadState) {
    val ready = downloadState as? UpdateDownloadState.Ready ?: return@LaunchedEffect
    onInstall(ready.file)
  }
}

/** Открывает ссылку во внешнем браузере. */
private fun openUrl(context: Context, url: String) {
  val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  runCatching { context.startActivity(intent) }.onFailure { Log.w("Settings", "open $url: ${it.message}") }
}

/** Системное меню «Поделиться» со ссылкой на приложение. */
private fun shareApp(context: Context) {
  val send =
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_app_text, Links.REPOSITORY))
    }
  val chooser =
    Intent.createChooser(send, context.getString(R.string.about_share_app))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  runCatching { context.startActivity(chooser) }.onFailure { Log.w("Settings", "share app: ${it.message}") }
}

/** Подпись кнопки, которая не влезает: медленно прокручивается влево-вправо. */
@Composable
private fun AutoScrollText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
  val scroll = rememberScrollState()
  // maxValue известен только после раскладки: когда он появляется, включаем «качели».
  LaunchedEffect(text, scroll.maxValue) {
    while (scroll.maxValue > 0) {
      scroll.animateScrollTo(scroll.maxValue, animationSpec = tween(durationMillis = 3500, easing = LinearEasing))
      delay(400)
      scroll.animateScrollTo(0, animationSpec = tween(durationMillis = 3500, easing = LinearEasing))
      delay(400)
    }
  }
  Row(modifier = modifier.horizontalScroll(scroll)) {
    // Боковые отступы внутри прокрутки: длинная подпись не липнет к краям кнопки,
    // а короткая (когда всё влезает) остаётся неподвижной.
    Text(
      text = text,
      style = style,
      maxLines = 1,
      softWrap = false,
      modifier = Modifier.padding(horizontal = 12.dp),
    )
  }
}

/** Карточка-секция в стиле «Загрузок»: заголовок + содержимое. */
@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(14.dp),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(modifier = Modifier.height(8.dp))
      content()
    }
  }
}

/** Инструмент (yt-dlp / ffmpeg): версия, статус и три кнопки в ряд равной ширины. */
@Composable
private fun ToolBlock(
  title: String,
  version: String?,
  referenceVersion: String,
  status: ToolStatus,
  actions: List<Pair<String, () -> Unit>>,
  onInstallBundled: () -> Unit,
) {
  val missing = version.isNullOrBlank()
  val outdated = !missing && version != referenceVersion
  val versionColor =
    when {
      missing -> MaterialTheme.colorScheme.error
      outdated -> Color(0xFFF9A825) // жёлтый: версия отличается от эталонной
      else -> SuccessGreen
    }

  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(text = title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
    Text(
      text = version ?: stringResource(R.string.version_missing),
      style = MaterialTheme.typography.bodySmall,
      color = versionColor,
    )
  }
  // Кнопки в ряд равной ширины: раньше они уезжали вправо из-за горизонтального скролла.
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    actions.forEach { (label, onClick) ->
      OutlinedButton(
        onClick = onClick,
        enabled = status !is ToolStatus.Running,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
      ) {
        AutoScrollText(text = label, style = MaterialTheme.typography.labelMedium)
      }
    }
  }
  if (missing) {
    Button(
      onClick = onInstallBundled,
      enabled = status !is ToolStatus.Running,
      modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
      Text(stringResource(R.string.install_from_app))
    }
  }
  StatusText(status)
}

@Composable
private fun StatusText(status: ToolStatus) {
  val (text, color) =
    when (status) {
      ToolStatus.Idle -> return
      is ToolStatus.Running ->
        (
          status.message ?: stringResource(R.string.status_running)
        ) + (status.percent?.let { " · $it%" } ?: "") to MaterialTheme.colorScheme.onSurfaceVariant
      is ToolStatus.Done -> status.text to MaterialTheme.colorScheme.primary
      is ToolStatus.Failed -> status.text to MaterialTheme.colorScheme.error
    }
  Text(
    text = text,
    modifier = Modifier.padding(top = 6.dp),
    style = MaterialTheme.typography.bodySmall,
    color = color,
  )
}

