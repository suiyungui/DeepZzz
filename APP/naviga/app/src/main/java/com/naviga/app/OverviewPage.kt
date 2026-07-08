package com.naviga.app

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun OverviewPage(
    context: android.content.Context,
    uiState: OverviewUiState = remember { sampleOverviewUiState() },
    streamUrl: String?,
    snapshotUrl: String?,
    cameraLive: Boolean,
    onCameraLiveChange: (Boolean) -> Unit,
    listenEnabled: Boolean,
    onListenToggle: () -> Unit,
    voiceRecording: Boolean,
    onVoicePressStart: () -> Unit,
    onVoicePressEnd: () -> Unit,
    onSnapshot: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onAcknowledge: (String) -> Unit,
) {
    LaunchedEffect(uiState.statusMetrics) {
        NotificationHelper.showStatusNotification(context, uiState.statusMetrics)
    }

    PageColumn {
        item {
            PageHeader(title = "总览")
        }
        item {
            LiveCameraCard(
                cameraLive = cameraLive,
                streamUrl = streamUrl,
                snapshotUrl = snapshotUrl,
                onCameraLiveChange = onCameraLiveChange,
                listenEnabled = listenEnabled,
                onListenToggle = onListenToggle,
                voiceRecording = voiceRecording,
                onVoicePressStart = onVoicePressStart,
                onVoicePressEnd = onVoicePressEnd,
                onSnapshot = onSnapshot,
                onToggleFullscreen = onToggleFullscreen,
            )
        }
        item {
            MetricBars(metrics = uiState.metrics)
        }
        if (uiState.events.isNotEmpty()) {
            item {
                SectionHeader(title = "最近警告")
            }
            items(uiState.events.size) { index ->
                val event = uiState.events[index]
                AlertEventCard(
                    event = event,
                    onAcknowledge = { onAcknowledge(event.id) },
                )
            }
        }
    }
}

@Composable
fun MetricBars(metrics: List<Metric>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        metrics.forEach { metric ->
            MetricBar(metric = metric)
        }
    }
}

@Composable
private fun MetricBar(metric: Metric) {
    PressedCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(icon = metric.icon)
            Text(
                text = metric.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = metric.value,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = metric.unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullscreenCameraView(
    streamUrl: String?,
    snapshotUrl: String?,
    cameraLive: Boolean,
    onCameraLiveChange: (Boolean) -> Unit,
    onExitFullscreen: () -> Unit,
) {
    val context = LocalView.current.context as Activity
    val view = LocalView.current
    val window = context.window
    val insetsController = WindowCompat.getInsetsController(window, view)

    DisposableEffect(Unit) {
        val original = context.requestedOrientation
        context.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            context.requestedOrientation = original
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler { onExitFullscreen() }

    var controlsVisible by remember { mutableStateOf(true) }
    val controlsAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "fullscreenControlsAlpha",
    )

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(3500)
            controlsVisible = false
        }
    }

    LaunchedEffect(cameraLive) {
        controlsVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controlsVisible = !controlsVisible },
    ) {
        CameraStreamView(
            streamUrl = streamUrl,
            snapshotUrl = snapshotUrl,
            cameraLive = cameraLive,
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = controlsAlpha }
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                onClick = onExitFullscreen,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "实时看护",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(8.dp))
            if (cameraLive) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "直播中",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (!cameraLive) {
            Surface(
                onClick = { onCameraLiveChange(true) },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .graphicsLayer { alpha = controlsAlpha }
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerButton(
                icon = if (cameraLive) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                onClick = { onCameraLiveChange(!cameraLive) },
            )
            PlayerButton(
                icon = Icons.Rounded.FullscreenExit,
                onClick = onExitFullscreen,
            )
        }
    }
}

@Composable
fun LiveCameraCard(
    cameraLive: Boolean,
    streamUrl: String?,
    snapshotUrl: String?,
    onCameraLiveChange: (Boolean) -> Unit,
    listenEnabled: Boolean,
    onListenToggle: () -> Unit,
    voiceRecording: Boolean,
    onVoicePressStart: () -> Unit,
    onVoicePressEnd: () -> Unit,
    onSnapshot: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black),
            ) {
                CameraStreamView(
                    streamUrl = streamUrl,
                    snapshotUrl = snapshotUrl,
                    cameraLive = cameraLive,
                    modifier = Modifier.fillMaxSize(),
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                            ),
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerButton(
                        icon = if (cameraLive) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        onClick = { onCameraLiveChange(!cameraLive) },
                    )
                    PlayerButton(
                        icon = Icons.Rounded.Fullscreen,
                        onClick = onToggleFullscreen,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniActionButton(
                    label = "倾听",
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    selected = listenEnabled,
                    onClick = onListenToggle,
                    enabled = cameraLive,
                    modifier = Modifier.weight(1f),
                )
                HoldActionButton(
                    label = if (voiceRecording) "松开发送" else "语音",
                    icon = if (voiceRecording) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    selected = voiceRecording,
                    onPressStart = onVoicePressStart,
                    onPressEnd = onVoicePressEnd,
                    enabled = cameraLive,
                    modifier = Modifier.weight(1f),
                )
                MiniActionButton(
                    label = "抓拍",
                    icon = Icons.Rounded.PhotoCamera,
                    selected = false,
                    onClick = onSnapshot,
                    enabled = cameraLive,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun CameraStreamView(
    streamUrl: String?,
    snapshotUrl: String?,
    cameraLive: Boolean,
    modifier: Modifier = Modifier,
) {
    if (streamUrl.isNullOrBlank()) {
        Box(modifier = modifier.background(Color.Black))
        return
    }

    val context = LocalView.current.context
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
        }
    }
    var retryKey by remember(streamUrl) { mutableStateOf(0) }
    var hasRenderedFrame by remember(streamUrl) { mutableStateOf(false) }
    var playbackState by remember(streamUrl) { mutableStateOf(Player.STATE_IDLE) }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    LaunchedEffect(player, streamUrl, retryKey) {
        hasRenderedFrame = false
        player.setMediaSource(streamMediaSource(streamUrl, retryKey))
        player.prepare()
        player.playWhenReady = cameraLive
    }

    LaunchedEffect(player, cameraLive) {
        if (cameraLive) {
            retryKey += 1
            player.playWhenReady = true
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
        } else {
            player.playWhenReady = false
            player.pause()
            player.stop()
        }
    }

    LaunchedEffect(player, streamUrl, cameraLive) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackStateValue: Int) {
                playbackState = playbackStateValue
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFrame = true
            }

            override fun onPlayerError(error: PlaybackException) {
                if (cameraLive) {
                    retryKey += 1
                }
            }
        }
        player.addListener(listener)
        try {
            var waitingForFrameMs = 0L
            var bufferingMs = 0L
            while (cameraLive) {
                delay(STREAM_HEALTH_CHECK_MS)
                if (!hasRenderedFrame) {
                    waitingForFrameMs += STREAM_HEALTH_CHECK_MS
                } else {
                    waitingForFrameMs = 0L
                }
                if (playbackState == Player.STATE_BUFFERING) {
                    bufferingMs += STREAM_HEALTH_CHECK_MS
                } else {
                    bufferingMs = 0L
                }
                if (
                    player.playbackState == Player.STATE_IDLE ||
                    player.playbackState == Player.STATE_ENDED ||
                    waitingForFrameMs >= STREAM_FIRST_FRAME_TIMEOUT_MS ||
                    bufferingMs >= STREAM_BUFFERING_TIMEOUT_MS
                ) {
                    waitingForFrameMs = 0L
                    bufferingMs = 0L
                    retryKey += 1
                }
            }
        } finally {
            player.removeListener(listener)
        }
    }

    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setKeepContentOnPlayerReset(true)
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { playerView ->
            playerView.player = player
        },
        onRelease = { playerView ->
            playerView.player = null
        },
    )
}

@Composable
fun SafetyZoneOverlay(
    safetyZone: SafetyZone?,
    editing: Boolean,
    zoneColor: Color = MaterialTheme.colorScheme.primary,
    onSafetyZoneChange: (SafetyZone) -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    val zone = safetyZone?.normalized()
    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(editing, canvasSize) {
                if (!editing || canvasSize.width <= 0 || canvasSize.height <= 0) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        dragStart = offset
                        dragCurrent = offset
                    },
                    onDrag = { change, _ ->
                        dragCurrent = change.position
                    },
                    onDragCancel = {
                        dragStart = null
                        dragCurrent = null
                    },
                    onDragEnd = {
                        val start = dragStart
                        val end = dragCurrent
                        if (start != null && end != null) {
                            val width = canvasSize.width.toFloat().coerceAtLeast(1f)
                            val height = canvasSize.height.toFloat().coerceAtLeast(1f)
                            onSafetyZoneChange(
                                SafetyZone(
                                    left = start.x / width,
                                    top = start.y / height,
                                    right = end.x / width,
                                    bottom = end.y / height,
                                ).normalized(),
                            )
                        }
                        dragStart = null
                        dragCurrent = null
                    },
                )
            },
    ) {
        val activeStart = dragStart
        val activeEnd = dragCurrent
        val left: Float
        val top: Float
        val right: Float
        val bottom: Float
        if (editing && activeStart != null && activeEnd != null) {
            left = minOf(activeStart.x, activeEnd.x).coerceIn(0f, size.width)
            top = minOf(activeStart.y, activeEnd.y).coerceIn(0f, size.height)
            right = maxOf(activeStart.x, activeEnd.x).coerceIn(0f, size.width)
            bottom = maxOf(activeStart.y, activeEnd.y).coerceIn(0f, size.height)
        } else if (zone != null) {
            left = zone.left * size.width
            top = zone.top * size.height
            right = zone.right * size.width
            bottom = zone.bottom * size.height
        } else {
            return@Canvas
        }
        val rectWidth = (right - left).coerceAtLeast(1f)
        val rectHeight = (bottom - top).coerceAtLeast(1f)
        drawRect(
            color = zoneColor.copy(alpha = 0.16f),
            topLeft = Offset(left, top),
            size = Size(rectWidth, rectHeight),
        )
        drawRect(
            color = zoneColor,
            topLeft = Offset(left, top),
            size = Size(rectWidth, rectHeight),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

private fun streamMediaSource(streamUrl: String, retryKey: Int): HlsMediaSource {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(STREAM_TIMEOUT_MS)
        .setReadTimeoutMs(STREAM_TIMEOUT_MS)
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(
            mapOf(
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
            ),
        )
    return HlsMediaSource.Factory(httpFactory)
        .setAllowChunklessPreparation(false)
        .createMediaSource(
            MediaItem.Builder()
                .setUri(streamUrl.withRetryKey(retryKey))
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(STREAM_TARGET_OFFSET_MS)
                        .setMinPlaybackSpeed(0.97f)
                        .setMaxPlaybackSpeed(1.08f)
                        .build(),
                )
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build(),
        )
}

private fun String.withRetryKey(retryKey: Int): String {
    val separator = if (contains("?")) "&" else "?"
    return "$this${separator}retry=$retryKey"
}

private const val STREAM_TIMEOUT_MS = 7_000
private const val STREAM_HEALTH_CHECK_MS = 1_500L
private const val STREAM_FIRST_FRAME_TIMEOUT_MS = 9_000L
private const val STREAM_BUFFERING_TIMEOUT_MS = 10_500L
private const val STREAM_TARGET_OFFSET_MS = 1_500L

@Composable
fun AlertEventCard(
    event: AlertEvent,
    onAcknowledge: () -> Unit,
) {
    PressedCard(
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(icon = event.icon, tint = MaterialTheme.colorScheme.error)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(text = event.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = event.timestamp,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onAcknowledge,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "标记已处理",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
