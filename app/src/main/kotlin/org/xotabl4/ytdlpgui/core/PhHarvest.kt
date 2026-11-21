package org.xotabl4.ytdlpgui.core

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.xotabl4.ytdlpgui.ui.PhHarvestActivity

/**
 * Мост между репозиторием/сервисом и [PhHarvestActivity].
 *
 * PornHub без TLS-impersonation отдаёт 403, поэтому варианты качества достаём из реального
 * WebView (он проходит age-gate), а прямые mp4/m3u8-ссылки затем качаем обычным yt-dlp.
 */
object PhHarvest {
  /** Один вариант качества со страницы PH. */
  data class PhVariant(val quality: Int, val format: String, val url: String)

  /** Результат работы скрытого окна: название видео + варианты качества. */
  data class PhResult(val title: String, val variants: List<PhVariant>)

  private var pending: CompletableDeferred<Pair<String, List<String>?>?>? = null

  @Volatile private var lastVariants: List<PhVariant> = emptyList()

  /** Открывает скрытое окно и возвращает список качеств (от лучшего к худшему). */
  suspend fun resolveAll(context: Context, pageUrl: String, timeoutMs: Long = 30_000L): PhResult {
    val deferred = CompletableDeferred<Pair<String, List<String>?>?>()
    pending = deferred
    val intent =
      Intent(context, PhHarvestActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(PhHarvestActivity.EXTRA_URL, pageUrl)
    context.startActivity(intent)
    val raw = withTimeoutOrNull(timeoutMs) { deferred.await() }
    pending = null
    val title = raw?.first.orEmpty().trim()
    val variants =
      raw?.second.orEmpty()
        .mapNotNull { line ->
          val parts = line.split('|', limit = 3)
          if (parts.size < 3) return@mapNotNull null
          val url = parts[2].trim()
          if (url.isEmpty()) return@mapNotNull null
          PhVariant(parts[0].toIntOrNull() ?: 0, parts[1].trim().ifEmpty { "mp4" }, url)
        }
        .sortedByDescending { it.quality }
    lastVariants = variants
    return PhResult(title, variants)
  }

  /** Ссылка на выбранное качество (или лучшее доступное). */
  fun urlForQuality(quality: Int?): String? {
    if (quality == null) return lastVariants.firstOrNull()?.url
    return lastVariants.firstOrNull { it.quality == quality }?.url ?: lastVariants.firstOrNull()?.url
  }

  fun variants(): List<PhVariant> = lastVariants

  /** Вызывается из Activity, когда список найден (или стало понятно, что не найти). */
  fun complete(title: String, list: List<String>?) {
    pending?.complete(title to list)
  }

  /** `view_video.php?viewkey=X` → `embed/X`: на embed-странице плеер стартует сразу. */
  fun embedUrl(pageUrl: String): String {
    if (pageUrl.contains("/embed/")) return pageUrl
    val key =
      Regex("""[?&]viewkey=([0-9a-zA-Z]+)""").find(pageUrl)?.groupValues?.getOrNull(1)
        ?: return pageUrl
    return "https://www.pornhub.com/embed/$key"
  }
}
