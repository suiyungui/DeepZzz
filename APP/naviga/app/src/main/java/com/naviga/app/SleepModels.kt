package com.naviga.app

enum class SleepEventType {
    Cry,
    Roll,
    Sleep,
    OffBed,
}

data class SleepSegment(val startHour: Float, val endHour: Float, val type: SleepEventType)

data class SleepUiState(
    val segments: List<SleepSegment>,
    val heatmapBlocks: List<List<Pair<Float, Float>>>,
)

val TimelineLanes = listOf(
    SleepEventType.Cry,
    SleepEventType.Roll,
    SleepEventType.Sleep,
)

val TimelineLabels = mapOf(
    SleepEventType.Cry to "哭闹",
    SleepEventType.Roll to "翻身",
    SleepEventType.Sleep to "睡眠",
)

val SegmentLaneIndex = mapOf(
    SleepEventType.Cry to 0,
    SleepEventType.Roll to 1,
    SleepEventType.Sleep to 2,
    SleepEventType.OffBed to 2,
)

val HeatmapDateLabels = listOf("5/22", "5/23", "5/24", "5/25", "5/26", "5/27", "5/28")
val HeatmapTimeLabels = listOf("00", "04", "08", "12", "16", "20", "24")

fun sampleSleepUiState(): SleepUiState {
    val rawSegments = sampleSleepSegments()
    return SleepUiState(
        segments = rawSegments + deriveSleepGaps(rawSegments),
        heatmapBlocks = sampleSleepHeatmap(),
    )
}

private fun sampleSleepSegments() = listOf(
    SleepSegment(14.6f, 15.0f, SleepEventType.Cry),
    SleepSegment(23.1f, 23.5f, SleepEventType.Cry),
    SleepSegment(1.6f, 2.0f, SleepEventType.Cry),
    SleepSegment(5.1f, 5.5f, SleepEventType.Cry),
    SleepSegment(22.0f, 23.0f, SleepEventType.Roll),
    SleepSegment(0.5f, 1.5f, SleepEventType.Roll),
    SleepSegment(2.5f, 3.5f, SleepEventType.Roll),
    SleepSegment(4.0f, 4.8f, SleepEventType.Roll),
    SleepSegment(6.0f, 7.0f, SleepEventType.OffBed),
    SleepSegment(16.0f, 18.0f, SleepEventType.OffBed),
)

private fun sampleSleepHeatmap() = listOf(
    listOf(21.0f to 24.0f, 0.0f to 6.5f, 13.0f to 14.5f),
    listOf(21.5f to 24.0f, 0.0f to 6.0f, 12.5f to 14.0f),
    listOf(20.5f to 24.0f, 0.0f to 7.0f, 13.5f to 15.0f),
    listOf(21.0f to 24.0f, 0.0f to 5.5f, 12.0f to 14.0f),
    listOf(22.0f to 24.0f, 0.0f to 6.0f, 13.0f to 14.5f),
    listOf(21.0f to 24.0f, 0.0f to 6.5f, 14.0f to 15.5f),
    listOf(21.5f to 24.0f, 0.0f to 6.0f, 12.5f to 14.0f),
)

private fun deriveSleepGaps(nonSleep: List<SleepSegment>): List<SleepSegment> {
    val occupied = nonSleep.filter { it.type != SleepEventType.Sleep }.sortedBy { it.startHour }
    if (occupied.isEmpty()) return listOf(SleepSegment(0f, 24f, SleepEventType.Sleep))

    val gaps = mutableListOf<SleepSegment>()
    if (occupied.first().startHour > 0f) {
        gaps.add(SleepSegment(0f, occupied.first().startHour, SleepEventType.Sleep))
    }

    for (i in 0 until occupied.size - 1) {
        val end = occupied[i].endHour
        val next = occupied[i + 1].startHour
        if (next - end > 0.05f) {
            gaps.add(SleepSegment(end, next, SleepEventType.Sleep))
        }
    }

    if (occupied.last().endHour < 24f) {
        gaps.add(SleepSegment(occupied.last().endHour, 24f, SleepEventType.Sleep))
    }

    return gaps
}
