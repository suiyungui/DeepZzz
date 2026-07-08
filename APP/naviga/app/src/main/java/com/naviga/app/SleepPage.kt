package com.naviga.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun rememberEventColors(): Map<SleepEventType, Color> {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        mapOf(
            SleepEventType.Cry to Color(0xFFE53935),
            SleepEventType.Roll to Color(0xFFFF8F00),
            SleepEventType.Sleep to scheme.primary,
            SleepEventType.OffBed to scheme.outline,
        )
    }
}


// ── Page ──────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepPage(
    uiState: SleepUiState = remember { sampleSleepUiState() },
) {
    val eventColors = rememberEventColors()

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outline

    PageColumn {
        item {
            PageHeader(title = "睡眠")
        }
        item {
            SleepSummaryCard(
                onSurface = onSurface,
                onSurfaceVar = onSurfaceVar,
            )
        }
        item {
            SleepTimelineCard(
                segments = uiState.segments,
                eventColors = eventColors,
                onSurface = onSurface,
                onSurfaceVar = onSurfaceVar,
                outline = outline,
            )
        }
        item {
            SleepHeatmapCard(
                sleepBlocks = uiState.heatmapBlocks,
                eventColors = eventColors,
                onSurfaceVar = onSurfaceVar,
                outline = outline,
            )
        }
    }
}

// ── Summary ───────────────────────────────────────────────────

@Composable
private fun SleepSummaryCard(
    onSurface: Color,
    onSurfaceVar: Color,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "昨晚睡眠",
                    style = MaterialTheme.typography.labelLarge,
                    color = onSurfaceVar,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "8 小时 32 分",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                )
            }
        }
    }
}

// ── Timeline ──────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimelineCard(
    segments: List<SleepSegment>,
    eventColors: Map<SleepEventType, Color>,
    onSurface: Color,
    onSurfaceVar: Color,
    outline: Color,
) {
    val textMeasurer = rememberTextMeasurer()

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TimelineLanes.forEach { type ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(eventColors.getValue(type)),
                        )
                        Text(
                            text = TimelineLabels.getValue(type),
                            style = MaterialTheme.typography.labelSmall,
                            color = onSurfaceVar,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            TimelineCanvas(
                segments = segments,
                eventColors = eventColors,
                onSurfaceVar = onSurfaceVar,
                outline = outline,
                textMeasurer = textMeasurer,
            )
        }
    }
}

@Composable
private fun TimelineCanvas(
    segments: List<SleepSegment>,
    eventColors: Map<SleepEventType, Color>,
    onSurfaceVar: Color,
    outline: Color,
    textMeasurer: TextMeasurer,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
    ) {
        drawTimeline(
            segments = segments,
            eventColors = eventColors,
            onSurfaceVar = onSurfaceVar,
            outline = outline,
            textMeasurer = textMeasurer,
        )
    }
}

private const val TIMELINE_LP = 36f
private const val TIMELINE_RP = 8f

private fun DrawScope.drawTimeline(
    segments: List<SleepSegment>,
    eventColors: Map<SleepEventType, Color>,
    onSurfaceVar: Color,
    outline: Color,
    textMeasurer: TextMeasurer,
) {
    val w = size.width
    val h = size.height
    val lp = TIMELINE_LP.dp.toPx()
    val rp = TIMELINE_RP.dp.toPx()
    val chartW = w - lp - rp
    val laneCount = TimelineLanes.size
    val laneH = (h - 24.dp.toPx()) / laneCount
    val hourToX = { hour: Float -> lp + (hour / 24f) * chartW }

    val gridColor = outline.copy(alpha = 0.08f)

    // Grid
    for (i in 0..laneCount) {
        val y = i * laneH
        drawLine(gridColor, Offset(lp, y), Offset(w - rp, y), strokeWidth = 0.5f)
    }
    for (hr in 0..24 step 6) {
        val x = hourToX(hr.toFloat())
        drawLine(gridColor, Offset(x, 0f), Offset(x, laneCount * laneH), strokeWidth = 0.5f)
    }

    // Segments
    segments.forEach { seg ->
        val lane = SegmentLaneIndex[seg.type] ?: return@forEach
        val x1 = hourToX(seg.startHour)
        val x2 = hourToX(seg.endHour)
        val y = lane * laneH + 3.dp.toPx()
        val barH = laneH - 6.dp.toPx()
        val alpha = if (seg.type == SleepEventType.OffBed) 0.18f else 0.32f
        drawRoundRect(
            color = eventColors.getValue(seg.type).copy(alpha = alpha),
            topLeft = Offset(x1, y),
            size = Size(x2 - x1, barH),
            cornerRadius = CornerRadius(4.dp.toPx()),
        )
    }

    // X-axis
    val axisStyle = TextStyle(fontSize = 9.sp, color = onSurfaceVar.copy(alpha = 0.6f))
    for (hr in 0..24 step 6) {
        val x = hourToX(hr.toFloat())
        val result = textMeasurer.measure("%02d".format(hr), axisStyle)
        drawText(
            textLayoutResult = result,
            topLeft = Offset(x - result.size.width / 2f, laneCount * laneH + 6.dp.toPx()),
        )
    }
}

// ── Heatmap ───────────────────────────────────────────────────

@Composable
private fun SleepHeatmapCard(
    sleepBlocks: List<List<Pair<Float, Float>>>,
    eventColors: Map<SleepEventType, Color>,
    onSurfaceVar: Color,
    outline: Color,
) {
    val textMeasurer = rememberTextMeasurer()
    val sleepColor = eventColors.getValue(SleepEventType.Sleep)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = "睡眠规律",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            ) {
                drawHeatmap(sleepBlocks, sleepColor, onSurfaceVar, outline, textMeasurer)
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun DrawScope.drawHeatmap(
    sleepBlocks: List<List<Pair<Float, Float>>>,
    sleepColor: Color,
    onSurfaceVar: Color,
    outline: Color,
    textMeasurer: TextMeasurer,
) {
    val w = size.width
    val h = size.height
    val lp = 32.dp.toPx()
    val rp = 8.dp.toPx()
    val tp = 4.dp.toPx()
    val bp = 18.dp.toPx()
    val chartW = w - lp - rp
    val chartH = h - tp - bp
    val colW = chartW / HeatmapDateLabels.size
    val rowH = chartH / (HeatmapTimeLabels.size - 1)

    val axisStyle = TextStyle(fontSize = 9.sp, color = onSurfaceVar.copy(alpha = 0.55f))
    val gridColor = outline.copy(alpha = 0.06f)
    val nightColor = outline.copy(alpha = 0.035f)
    val blockColor = sleepColor.copy(alpha = 0.38f)

    // Y-axis + grid
    HeatmapTimeLabels.forEachIndexed { i, label ->
        val y = tp + i * rowH
        val result = textMeasurer.measure(label, axisStyle)
        drawText(
            textLayoutResult = result,
            topLeft = Offset(lp - result.size.width - 5.dp.toPx(), y - result.size.height / 2f),
        )
        if (i > 0) drawLine(gridColor, Offset(lp, y), Offset(w - rp, y), strokeWidth = 0.5f)
    }

    // Night bands
    val nightTop = tp + (20f / 24f) * chartH
    drawRoundRect(
        color = nightColor,
        topLeft = Offset(lp, nightTop),
        size = Size(chartW, tp + chartH - nightTop),
        cornerRadius = CornerRadius(2.dp.toPx()),
    )
    drawRoundRect(
        color = nightColor,
        topLeft = Offset(lp, tp),
        size = Size(chartW, tp + (6f / 24f) * chartH - tp),
        cornerRadius = CornerRadius(2.dp.toPx()),
    )

    // Sleep blocks
    HeatmapDateLabels.indices.forEach { col ->
        val x = lp + col * colW + 2.dp.toPx()
        val bw = colW - 4.dp.toPx()
        sleepBlocks[col].forEach { (start, end) ->
            val y1 = tp + (start / 24f) * chartH
            val y2 = tp + (end / 24f) * chartH
            if (y2 > y1) {
                drawRoundRect(
                    color = blockColor,
                    topLeft = Offset(x, y1),
                    size = Size(bw, y2 - y1),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
    }

    // X-axis dates
    HeatmapDateLabels.forEachIndexed { col, label ->
        val result = textMeasurer.measure(label, axisStyle)
        val x = lp + col * colW + colW / 2f
        drawText(
            textLayoutResult = result,
            topLeft = Offset(x - result.size.width / 2f, h - bp + 4.dp.toPx()),
        )
    }
}
