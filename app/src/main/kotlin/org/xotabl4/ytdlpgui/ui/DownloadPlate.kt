package org.xotabl4.ytdlpgui.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Random
import org.xotabl4.ytdlpgui.ui.theme.Emerald

/** Одна «пылинка» в стекле плашки. */
private class PlateDot(
  val baseX: Float,
  val y: Float,
  val speed: Float,
  val radiusDp: Float,
  val alpha: Float,
)

/** Сколько пылинок рисуем внутри заливки. */
private const val PLATE_DOT_COUNT = 140

/** Базовая скорость: доля ширины плашки за секунду (полный проход ~1,3 с). */
private const val PLATE_DOT_SPEED = 0.75f

/**
 * «Плашка загрузки» в духе мода Nice Research Tab (RimWorld): тёмная полоса, которая
 * заполняется слева направо **полупрозрачным зелёным «стеклом»** (без скоса, край вертикальный).
 *
 * Внутри заливки — мелкая «пыль», летящая справа налево (против роста полосы). Движение
 * считается от системных кадров (`withFrameMillis`), а не от зацикленной анимации, поэтому
 * картинка не «перезапускается»: каждая пылинка просто уходит за левый край и появляется справа.
 */
@Composable
fun DownloadPlate(
  title: String,
  progress: Float,
  modifier: Modifier = Modifier,
  trailing: String? = null,
) {
  val fraction = progress.coerceIn(0f, 1f)
  val track = MaterialTheme.colorScheme.surfaceVariant
  val glass = Emerald.copy(alpha = 0.45f)
  val glassEdge = Emerald.copy(alpha = 0.95f)

  // Непрерывное время кадров: без сброса фазы, поэтому анимация не «начинается заново».
  var frameTimeMs by remember { mutableLongStateOf(0L) }
  LaunchedEffect(Unit) {
    while (true) {
      withFrameMillis { frameTimeMs = it }
    }
  }

  val dots =
    remember {
      val random = Random(20261109L)
      List(PLATE_DOT_COUNT) {
        PlateDot(
          baseX = random.nextFloat(),
          y = random.nextFloat(),
          speed = 0.7f + random.nextFloat() * 0.6f,
          radiusDp = 0.45f + random.nextFloat() * 0.4f,
          alpha = 0.12f + random.nextFloat() * 0.26f,
        )
      }
    }

  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .height(56.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(track)
  ) {
    Canvas(modifier = Modifier.matchParentSize()) {
      val fillWidth = size.width * fraction
      if (fillWidth <= 0f) return@Canvas

      // «Зелёное стекло»: полупрозрачная заливка с лёгким вертикальным градиентом.
      drawRect(
        brush =
          Brush.verticalGradient(
            colors = listOf(glass.copy(alpha = 0.55f), glass.copy(alpha = 0.38f)),
            startY = 0f,
            endY = size.height,
          ),
        size = Size(fillWidth, size.height),
      )

      // Мелкая пыль внутри заливки: каждое движение непрерывно, без общей фазы.
      val seconds = frameTimeMs / 1000f
      dots.forEach { dot ->
        val x01 = ((dot.baseX - seconds * PLATE_DOT_SPEED * dot.speed) % 1f + 1f) % 1f
        val x = x01 * fillWidth
        if (x < 0f || x > fillWidth) return@forEach
        drawCircle(
          color = Color.White.copy(alpha = dot.alpha),
          radius = dot.radiusDp.dp.toPx(),
          center = Offset(x, dot.y * size.height),
        )
      }

      // Вертикальный «срез» стекла — тонкая светлая линия на границе заполнения.
      drawLine(
        color = glassEdge,
        start = Offset(fillWidth, 0f),
        end = Offset(fillWidth, size.height),
        strokeWidth = 1.5.dp.toPx(),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = title.ifBlank { "…" },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (trailing != null) {
        Text(
          text = trailing,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}
