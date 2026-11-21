package org.xotabl4.ytdlpgui.ui.link

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.xotabl4.ytdlpgui.R
import org.xotabl4.ytdlpgui.core.DownloadManager
import org.xotabl4.ytdlpgui.core.DownloadState
import org.xotabl4.ytdlpgui.core.DownloadStorage
import org.xotabl4.ytdlpgui.core.FormatItem
import org.xotabl4.ytdlpgui.core.FormatPicker
import org.xotabl4.ytdlpgui.core.Formats
import org.xotabl4.ytdlpgui.core.FormatsParser
import org.xotabl4.ytdlpgui.core.ShareReceiver
import org.xotabl4.ytdlpgui.core.YtDlp
import org.xotabl4.ytdlpgui.core.YtDlpState
import org.xotabl4.ytdlpgui.service.DownloadService
import org.xotabl4.ytdlpgui.ui.DownloadProgressBlock
import org.xotabl4.ytdlpgui.ui.rememberNotificationPermissionRequester

/** Экран загрузки: ссылка, форматы, прогресс. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkScreen(
  onOpenHistory: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LinkViewModel = viewModel(),
) {
  val url by viewModel.url.collectAsStateWithLifecycle()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val category by viewModel.category.collectAsStateWithLifecycle()
  val conversion by viewModel.conversion.collectAsStateWithLifecycle()
  val selection by viewModel.selection.collectAsStateWithLifecycle()
  val downloadState by DownloadManager.state.collectAsStateWithLifecycle()
  val downloadUrl by DownloadManager.activeUrl.collectAsStateWithLifecycle()
  val isLoading = state is LinkUiState.Loading

  val context = LocalContext.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val focusManager = LocalFocusManager.current
  // Разрешение на уведомления просим только при запуске загрузки (см. NotificationPermission.kt).
  val ensureNotifications = rememberNotificationPermissionRequester()

  // Ссылка из системного «Поделиться»: вставляем в поле и сразу запрашиваем форматы.
  val sharedLink by ShareReceiver.event.collectAsStateWithLifecycle()
  LaunchedEffect(sharedLink?.id) {
    val link = sharedLink ?: return@LaunchedEffect
    viewModel.applySharedUrl(link.url)
    ShareReceiver.consume()
  }

  val openDownloaded: (DownloadState.Completed) -> Unit = { done ->
    val intent =
      Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(done.uri), done.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    runCatching { context.startActivity(intent) }
      .onFailure { Log.w("LinkScreen", "Нет приложения для ${done.mimeType}", it) }
  }

  // Пока форматов нет, окно ввода стоит по центру вкладки; после загрузки списка
  // страница становится обычным скроллом сверху.
  val centered = state !is LinkUiState.Success

  Box(modifier = modifier.fillMaxSize()) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .align(if (centered) Alignment.Center else Alignment.TopCenter)
          .then(if (centered) Modifier else Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
          // Тап вне поля ввода закрывает клавиатуру.
          .pointerInput(Unit) { detectTapGestures(onTap = { keyboardController?.hide() }) }
          .padding(horizontal = 16.dp)
    ) {
    UrlPill(
      url = url,
      isLoading = isLoading,
      onUrlChange = viewModel::onUrlChange,
      onClear = {
        viewModel.onClear()
        // Крестик чистит ссылку и возвращает экран в исходный вид.
        focusManager.clearFocus()
        keyboardController?.hide()
      },
      onFetch = viewModel::fetch,
    )
    // Загрузка может идти и без форматов, поэтому прогресс показываем и здесь.
    val showsDownload =
      downloadState is DownloadState.Running ||
        (downloadState !is DownloadState.Idle && downloadUrl == url.trim())
    if (state !is LinkUiState.Success && showsDownload) {
      DownloadProgressBlock(
        state = downloadState,
        onCancel = { DownloadManager.cancel() },
        onRetry = { DownloadManager.retry(context) },
        onOpen = openDownloaded,
      )
    }
    when (val current = state) {
      LinkUiState.Idle -> Hint(stringResource(R.string.link_idle))
      LinkUiState.Loading -> LoadingBlock()
      is LinkUiState.Error -> ErrorBlock(current.message, onRetry = viewModel::fetch)
      is LinkUiState.Success ->
        FormatsBlock(
          formats = current.formats,
          category = category,
          selection = selection,
          downloadState = downloadState,
          conversion = conversion,
          conversionOptions = category.conversionOptions,
          onConversionChange = viewModel::setConversion,
          onSelectCategory = viewModel::selectCategory,
          onSelectContainer = viewModel::selectContainer,
          onSelectFormat = viewModel::selectFormat,
          onDownload = { formatId ->
            ensureNotifications {
              DownloadService.start(context)
              DownloadManager.start(
                context,
                url.trim(),
                formatId,
                conversion,
                thumbnail = current.formats.thumbnail,
                durationSeconds = current.formats.durationSeconds,
                title = current.formats.title,
                playlistTitle = current.formats.playlistTitle,
                isPlaylist = current.formats.isPlaylist,
              )
            }
          },
          onCancel = { DownloadManager.cancel() },
          onRetry = { DownloadManager.retry(context) },
          onOpen = openDownloaded,
        )
      }
    }
  }
}



@Composable
private fun UrlPill(
  url: String,
  isLoading: Boolean,
  onUrlChange: (String) -> Unit,
  onClear: () -> Unit,
  onFetch: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.inverseSurface,
    shape = RoundedCornerShape(28.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Box {
          // BasicTextField без декораций: подсказка «Paste video link» в стиле концепта.
          BasicTextField(
            value = url,
            onValueChange = onUrlChange,
            singleLine = true,
            textStyle =
              MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.inverseOnSurface, fontWeight = FontWeight.Medium),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onFetch() }),
            modifier = Modifier.fillMaxWidth().testTag("link-input"),
          )
          if (url.isEmpty()) {
            Text(
              text = stringResource(R.string.link_hint),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.65f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Text(
          text = stringResource(R.string.home_pill_sub),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.5f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (url.isNotEmpty()) {
        Text(
          text = "✕",
          modifier = Modifier.clickable(onClick = onClear).padding(4.dp),
          color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.6f),
          style = MaterialTheme.typography.titleMedium,
        )
      }
      Button(
        onClick = onFetch,
        enabled = url.isNotBlank() && !isLoading,
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        modifier = Modifier.testTag("fetch-button"),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            // На светлой «пилюле» стандартный disabled-цвет теряется — задаём свой.
            disabledContainerColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.10f),
            disabledContentColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.45f),
          ),
      ) {
        Text(stringResource(R.string.link_fetch), fontWeight = FontWeight.Bold)
      }
    }
  }
}

@Composable
private fun Hint(text: String) {
  Text(
    text = text,
    modifier = Modifier.padding(16.dp),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun LoadingBlock() {
  Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.height(8.dp))
    Text(stringResource(R.string.link_loading), style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
    Text(
      text = "${stringResource(R.string.link_error_prefix)}: $message",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.error,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.link_retry)) }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatsBlock(
  formats: Formats,
  category: FormatCategory,
  selection: FormatSelection,
  downloadState: DownloadState,
  conversion: String,
  conversionOptions: List<String>,
  onConversionChange: (String) -> Unit,
  onSelectCategory: (FormatCategory) -> Unit,
  onSelectContainer: (FormatCategory, String?) -> Unit,
  onSelectFormat: (FormatCategory, FormatItem?) -> Unit,
  onDownload: (String) -> Unit,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
  onOpen: (DownloadState.Completed) -> Unit,
) {
  val separate = needsSeparateStreams(conversion, formats)
  val items =
    when (category) {
      FormatCategory.VideoAudio -> formats.videoAudio
      FormatCategory.VideoOnly -> formats.videoOnly
      FormatCategory.AudioOnly -> formats.audioOnly
    }
  val counts =
    mapOf(
      FormatCategory.VideoAudio to formats.videoAudio.size,
      FormatCategory.VideoOnly to formats.videoOnly.size,
      FormatCategory.AudioOnly to formats.audioOnly.size,
    )
  val container = selection.containerFor(category)
  val shown = FormatPicker.sortedByQuality(FormatPicker.withContainer(items, container))
  val chosen = selection.itemFor(category)
  val formatId = FormatPicker.idFor(category.kind, selection.combined, selection.video, selection.audio)
  val expected = FormatPicker.bytesFor(category.kind, selection.combined, selection.video, selection.audio)
  // Какое окно выбора открыто: основной список категории или аудиодорожки (при конверсии).
  var sheet by rememberSaveable { mutableStateOf<String?>(null) }

  Column(modifier = Modifier.fillMaxSize()) {
    Text(
      text = formats.title,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      style = MaterialTheme.typography.titleMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    if (formats.isPlaylist) {
      Hint(
        if (formats.playlistCount > 0) {
          stringResource(R.string.format_playlist_hint, formats.playlistCount)
        } else {
          stringResource(R.string.format_playlist_hint_unknown)
        },
      )
    }
    if (separate) {
      Hint(stringResource(R.string.format_conversion_requires_parts))
    } else if (conversion != ORIGINAL_FORMAT) {
      Hint(stringResource(R.string.format_no_separate_streams))
    }
    DownloadSection(
      canDownload = formatId != null,
      expectedBytes = expected,
      downloadState = downloadState,
      conversion = conversion,
      conversionOptions = conversionOptions,
      onConversionChange = onConversionChange,
      onDownload = { formatId?.let(onDownload) },
      onCancel = onCancel,
      onRetry = onRetry,
      onOpen = onOpen,
    )
    TabRow(selectedTabIndex = category.ordinal) {
      FormatCategory.entries.forEach { entry ->
        Tab(
          selected = entry == category,
          // «Видео + аудио» недоступно, когда для конверсии нужны отдельные потоки.
          enabled = entry != FormatCategory.VideoAudio || !separate,
          onClick = { onSelectCategory(entry) },
          text = { Text(text = "${stringResource(entry.labelRes)} (${counts[entry] ?: 0})") },
        )
      }
    }
    ContainerChips(items = items, current = container, onSelect = { onSelectContainer(category, it) })
    PickerRow(
      label = stringResource(if (category == FormatCategory.AudioOnly) R.string.format_audio else R.string.format_video),
      chosen = chosen,
      auto = FormatPicker.best(shown),
      onClick = { sheet = SHEET_MAIN },
    )
    if (separate) {
      PickerRow(
        label = stringResource(R.string.format_audio),
        chosen = selection.audio,
        auto = FormatPicker.best(formats.audioOnly),
        onClick = { sheet = SHEET_AUDIO },
      )
    }
    if (shown.isEmpty()) Hint(stringResource(R.string.formats_empty))

    val audioSheet = sheet == SHEET_AUDIO
    if (sheet != null) {
      QualitySheet(
        title = stringResource(
          if (audioSheet || category == FormatCategory.AudioOnly) R.string.format_audio
          else R.string.format_quality
        ),
        options = if (audioSheet) formats.audioOnly else shown,
        chosen = if (audioSheet) selection.audio else chosen,
        onPick = { item ->
          onSelectFormat(if (audioSheet) FormatCategory.AudioOnly else category, item)
          sheet = null
        },
        onDismiss = { sheet = null },
      )
    }
  }
}

@Composable
private fun DownloadSection(
  canDownload: Boolean,
  expectedBytes: Long,
  downloadState: DownloadState,
  conversion: String,
  conversionOptions: List<String>,
  onConversionChange: (String) -> Unit,
  onDownload: () -> Unit,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
  onOpen: (DownloadState.Completed) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
    if (!canDownload) {
      Text(
        text = stringResource(R.string.download_select_format),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Button(
        onClick = onDownload,
        modifier = Modifier.fillMaxWidth(),
        // Параллельные загрузки разрешены: лишние задачи встанут в очередь
        // (см. MAX_PARALLEL_DOWNLOADS), поэтому кнопку не блокируем.
        enabled = true,
      ) {
        Text(stringResource(R.string.download_start))
      }
      if (expectedBytes > 0L) {
        Text(
          text = stringResource(R.string.format_expected_size, FormatsParser.sizeLabel(expectedBytes)),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
    ConversionSelector(current = conversion, options = conversionOptions, onChange = onConversionChange)
    DownloadProgressBlock(
      state = downloadState,
      onCancel = onCancel,
      onRetry = onRetry,
      onOpen = onOpen,
    )
  }
}

@Composable
private fun ConversionSelector(current: String, options: List<String>, onChange: (String) -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
    Text(
      text = stringResource(R.string.convert_to, conversionLabel(current)),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      options.forEach { option ->
        FilterChip(
          selected = option == current,
          onClick = { onChange(option) },
          label = { Text(conversionLabel(option)) },
        )
      }
    }
    if (current == "avi") {
      Text(
        text = stringResource(R.string.avi_warning),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
  }
}

@Composable
private fun conversionLabel(option: String): String =
  if (option == ORIGINAL_FORMAT) stringResource(R.string.conversion_original) else option

/** Значения для [FormatsBlock]: какой список качеств открыт в нижнем окне. */
private const val SHEET_MAIN = "main"
private const val SHEET_AUDIO = "audio"

/** Чипы контейнеров: «Все», mp4, webm, … — вместо простыни из всех форматов. */
@Composable
private fun ContainerChips(items: List<FormatItem>, current: String?, onSelect: (String?) -> Unit) {
  val containers = FormatPicker.containers(items)
  if (containers.size < 2) return
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FilterChip(
      selected = current == null,
      onClick = { onSelect(null) },
      label = { Text(stringResource(R.string.format_container_all)) },
    )
    containers.forEach { container ->
      FilterChip(
        selected = current == container,
        onClick = { onSelect(container) },
        label = { Text(container) },
      )
    }
  }
}

/** Строка выбора качества. */
@Composable
private fun PickerRow(label: String, chosen: FormatItem?, auto: FormatItem?, onClick: () -> Unit) {
  val shown = chosen ?: auto
  Surface(
    modifier =
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick),
    color = MaterialTheme.colorScheme.surfaceVariant,
    shape = RoundedCornerShape(12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = if (shown == null) stringResource(R.string.formats_empty) else qualityTitle(shown, auto),
          style = MaterialTheme.typography.bodyLarge,
        )
        if (shown != null) {
          Text(
            text = if (shown.hasVideo) FormatPicker.detail(shown) else FormatPicker.audioDetail(shown),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Text(text = "▾", style = MaterialTheme.typography.titleMedium)
    }
  }
}

/** «Автоматически · 2160p 60fps HDR» для лучшего качества, иначе «2160p 60fps HDR» / «129k». */
@Composable
private fun qualityTitle(item: FormatItem, auto: FormatItem?): String {
  val quality =
    FormatPicker.qualityTitle(
      item,
      videoLabel = stringResource(R.string.format_video),
      audioLabel = stringResource(R.string.format_audio),
    )
  val badge = FormatPicker.badge(item)
  val name = if (badge == null) quality else "$quality $badge"
  return if (item.downloadId == auto?.downloadId) {
    "${stringResource(R.string.format_quality_auto)} · $name"
  } else {
    name
  }
}

/** Нижнее окно со списком качеств. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualitySheet(
  title: String,
  options: List<FormatItem>,
  chosen: FormatItem?,
  onPick: (FormatItem) -> Unit,
  onDismiss: () -> Unit,
) {
  val sorted = FormatPicker.sortedByQuality(options)
  val auto = sorted.firstOrNull()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onDismiss) { Text("‹", style = MaterialTheme.typography.titleLarge) }
      Text(text = title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
      TextButton(onClick = onDismiss) { Text("✕", style = MaterialTheme.typography.titleMedium) }
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
      item {
        QualityRow(
          title = stringResource(R.string.format_quality_auto),
          detail = auto?.let { if (it.hasVideo) FormatPicker.detail(it) else FormatPicker.audioDetail(it) }.orEmpty(),
          badge = null,
          selected = chosen == null || chosen.downloadId == auto?.downloadId,
          onClick = { if (auto != null) onPick(auto) else onDismiss() },
        )
      }
      itemsIndexed(sorted) { _, item ->
        QualityRow(
          title =
            FormatPicker.qualityTitle(
              item,
              videoLabel = stringResource(R.string.format_video),
              audioLabel = stringResource(R.string.format_audio),
            ),
          detail = if (item.hasVideo) FormatPicker.detail(item) else FormatPicker.audioDetail(item),
          badge = FormatPicker.badge(item),
          selected = chosen?.downloadId == item.downloadId,
          onClick = { onPick(item) },
        )
      }
      item { Spacer(modifier = Modifier.height(12.dp)) }
    }
  }
}

/** Строка списка: «1080p [FHD]» + «mp4 · V: avc1 / A: mp4a · 105 MB». */
@Composable
private fun QualityRow(
  title: String,
  detail: String,
  badge: String?,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onClick)
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        if (badge != null) {
          Text(
            text = badge,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier =
              Modifier.padding(start = 8.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }
      if (detail.isNotBlank()) {
        Text(
          text = detail,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}


