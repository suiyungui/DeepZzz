package com.naviga.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.naviga.app.ui.theme.NavigaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var performanceModeController: PerformanceModeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.initChannels(this)
        NotificationHelper.requestPermission(this)
        ContextCompat.startForegroundService(this, Intent(this, CareForegroundService::class.java))
        performanceModeController = PerformanceModeController(this)
        lifecycle.addObserver(performanceModeController)
        setContent {
            NavigaRoot()
    }
}

@Composable
private fun NavigaRoot() {
    val systemDark = isSystemInDarkTheme()
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.System.name) }
    val mode = ThemeMode.valueOf(themeMode)
    val darkTheme = when (mode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> systemDark
    }

    NavigaTheme(darkTheme = darkTheme) {
        NavigaApp(
            themeMode = mode,
            onThemeModeChange = { themeMode = it.name },
        )
    }
}

@Composable
private fun NavigaApp(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Overview) }
    val view = LocalView.current
    val boardConnection = remember { BoardConnectionSession() }
    val boardApi = boardConnection.api
    val cameraStreamUrl = remember(boardApi) { boardApi.cameraStreamUrl() }
    val cameraSnapshotUrl = remember(boardApi) { boardApi.snapshotUrl() }
    val environmentDataSource = remember(boardApi) { BoardEnvironmentDataSource(boardApi) }
    val alertDataSource = remember(boardApi) { BoardAlertDataSourceAdapter(boardApi) }
    var environmentReading by remember { mutableStateOf(StubEnvironmentDataSource().currentReading()) }
    var retainedAlerts by remember { mutableStateOf(emptyMap<BoardAlertType, BoardAlert>()) }
    val albumStore = remember(view.context) { GrowthAlbumStore(view.context) }
    val albumImageLoader = remember(view.context) { GrowthAlbumImageLoader(view.context) }
    val albumSyncClient = remember(boardApi) { BoardGrowthAlbumSyncClient(boardApi) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val talkAudioStreamer = remember(view.context, scope) {
        TalkAudioStreamer(view.context.applicationContext, scope)
    }
    val growthMoments = remember(albumStore) { albumStore.loadMoments().toMutableStateList() }
    var albumFolderLabel by remember(albumStore) { mutableStateOf(albumStore.albumFolderLabel()) }
    val cameraState = rememberCameraUiState()
    val controlsState = rememberControlsUiState()
    val alertSettingsState = rememberAlertSettingsState()
    val familySettingsState = rememberFamilySettingsState()
    var acknowledgedAlertIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val notifiedAlertIds = remember { mutableSetOf<String>() }
    val musicFilePlayer = remember(view.context, boardApi) {
        MusicFilePlayer(view.context.applicationContext) { boardApi }
    }
    var selectedMusicTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var musicStatus by remember { mutableStateOf("请选择摇篮曲文件") }
    var musicPlaying by remember { mutableStateOf(false) }
    var musicBusy by remember { mutableStateOf(false) }
    var musicJob by remember { mutableStateOf<Job?>(null) }
    var musicStatusLoaded by remember { mutableStateOf(false) }

    val window = (view.context as Activity).window
    val insetsController = WindowCompat.getInsetsController(window, view)

    fun toggleFullscreen() {
        cameraState.fullscreen = !cameraState.fullscreen
        if (cameraState.fullscreen) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }
    val refreshAlbumMoments = {
        growthMoments.clear()
        growthMoments.addAll(albumStore.loadMoments())
    }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && talkAudioStreamer.startVoiceMessage(
                boardApiProvider = { boardApi },
                onSent = { sent, durationMillis ->
                    cameraState.voiceRecording = false
                    showMessage(
                        when {
                            durationMillis < 350L -> "语音太短"
                            sent -> "语音已发送"
                            else -> "语音发送失败"
                        },
                    )
                },
            )
        ) {
            cameraState.voiceRecording = true
            showMessage("按住说话")
        } else {
            showMessage("需要麦克风权限")
        }
    }
    val musicFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            view.context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        musicJob?.cancel()
        musicPlaying = false
        musicBusy = true
        musicStatus = "正在读取摇篮曲文件"
        musicJob = scope.launch {
            val track = withContext(Dispatchers.IO) {
                runCatching { musicFilePlayer.loadTrack(uri) }.getOrNull()
            }
            if (track != null) {
                selectedMusicTrack = track
                musicPlaying = false
                musicStatus = "已选择：${track.title}"
            } else {
                musicStatus = "读取摇篮曲文件失败"
                showMessage("读取摇篮曲文件失败")
            }
            musicBusy = false
        }
    }

    fun playSelectedMusic() {
        val track = selectedMusicTrack
        if (track == null) {
            showMessage("请先选择摇篮曲文件")
            return
        }
        if (!controlsState.lullabyEnabled) {
            showMessage("摇篮曲开关已关闭")
            return
        }
        if (musicBusy) return
        musicJob?.cancel()
        musicBusy = true
        musicPlaying = false
        musicStatus = if (track.boardTrackId != null) "正在启动板端摇篮曲" else "正在解码并发送到板端"
        musicJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                musicFilePlayer.play(track, controlsState.lullabyLevel)
            }
            musicBusy = false
            result.track?.let { selectedMusicTrack = it }
            musicPlaying = result.ok
            musicStatus = result.message
            showMessage(result.message)
        }
    }

    fun stopSelectedMusic() {
        musicJob?.cancel()
        musicBusy = true
        musicStatus = "正在停止板端播放"
        musicJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                musicFilePlayer.stop()
            }
            musicBusy = false
            musicPlaying = false
            musicStatus = result.message
            showMessage(result.message)
        }
    }

    fun syncBoardLullaby(showError: Boolean = false) {
        if (musicBusy) return
        musicJob = scope.launch {
            val status = withContext(Dispatchers.IO) {
                boardApi.lullabyStatus()
            }
            musicStatusLoaded = true
            if (status == null) {
                if (showError) showMessage("读取板端摇篮曲状态失败")
                if (selectedMusicTrack == null) musicStatus = "无法读取板端摇篮曲"
                return@launch
            }
            val boardTrack = status.currentTrack ?: status.tracks.firstOrNull()
            if (boardTrack != null) {
                selectedMusicTrack = boardTrack.toMusicTrack()
                musicPlaying = status.playing
                status.volume?.let { controlsState.lullabyLevel = it.coerceIn(0f, 1f) }
                musicStatus = if (status.playing) {
                    "板端循环播放中：${boardTrack.title}"
                } else {
                    "板端已存储：${boardTrack.title}"
                }
            } else if (selectedMusicTrack == null) {
                musicPlaying = false
                musicStatus = "请选择摇篮曲文件"
            }
        }
    }

    fun toggleListen() {
        if (cameraState.listenEnabled) {
            talkAudioStreamer.stopListening()
            cameraState.listenEnabled = false
            showMessage("倾听已关闭")
            return
        }
        if (talkAudioStreamer.startListening { boardApi }) {
            cameraState.listenEnabled = true
            showMessage("倾听已开启")
        } else {
            showMessage("倾听启动失败")
        }
    }

    fun startVoiceMessage() {
        if (cameraState.voiceRecording) return
        val granted = ContextCompat.checkSelfPermission(
            view.context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val started = talkAudioStreamer.startVoiceMessage(
            boardApiProvider = { boardApi },
            onSent = { sent, durationMillis ->
                cameraState.voiceRecording = false
                showMessage(
                    when {
                        durationMillis < 350L -> "语音太短"
                        sent -> "语音已发送"
                        else -> "语音发送失败"
                    },
                )
            },
        )
        if (started) {
            cameraState.voiceRecording = true
        } else {
            showMessage("语音启动失败")
        }
    }

    fun finishVoiceMessage() {
        if (!cameraState.voiceRecording) return
        talkAudioStreamer.finishVoiceMessage()
    }

    fun applyBoardActivityZone(activityZone: BoardActivityZone) {
        cameraState.safetyZone = activityZone.zone?.normalized()
        cameraState.activityZoneMode = activityZone.mode
    }

    fun syncBoardActivityZone() {
        scope.launch {
            val activityZone = withContext(Dispatchers.IO) {
                boardApi.activityZone()
            }
            if (activityZone != null) {
                applyBoardActivityZone(activityZone)
            }
        }
    }

    fun saveBoardActivityZone(zone: SafetyZone, mode: ActivityZoneMode) {
        scope.launch {
            withContext(Dispatchers.IO) {
                boardApi.setActivityZone(zone.normalized(), mode)
            }
        }
    }

    fun clearBoardActivityZone() {
        scope.launch {
            withContext(Dispatchers.IO) {
                boardApi.clearActivityZone()
            }
        }
    }

    LaunchedEffect(
        selectedTab,
        environmentDataSource,
        alertDataSource,
        alertSettingsState.faceAlert,
        alertSettingsState.noPersonAlert,
        alertSettingsState.cryAlert,
        alertSettingsState.boundaryAlert,
        cameraState.safetyZone,
        cameraState.activityZoneMode,
    ) {
        if (selectedTab != AppTab.Overview) return@LaunchedEffect
        while (true) {
            val boardActivityZone = withContext(Dispatchers.IO) {
                boardApi.activityZone()
            }
            if (boardActivityZone != null) {
                applyBoardActivityZone(boardActivityZone)
            }
            val settings = alertSettingsState.snapshot(
                safetyZone = cameraState.safetyZone,
                activityZoneMode = cameraState.activityZoneMode,
            )
            val reading = withContext(Dispatchers.IO) {
                environmentDataSource.currentReading()
            }
            val alerts = withContext(Dispatchers.IO) {
                alertDataSource.activeAlerts(settings)
            }
            environmentReading = reading
            retainedAlerts = retainedAlerts.withLatestAlerts(alerts)
            delay(OVERVIEW_REFRESH_INTERVAL_MS)
        }
    }

    LaunchedEffect(boardApi) {
        boardConnection.checkHealth()
        syncBoardActivityZone()
        syncBoardLullaby()
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == AppTab.Controls && !musicBusy) {
            syncBoardLullaby()
        }
    }

    LaunchedEffect(controlsState.lullabyLevel, musicPlaying, controlsState.lullabyEnabled) {
        if (!musicPlaying || !controlsState.lullabyEnabled) return@LaunchedEffect
        delay(MUSIC_VOLUME_SYNC_DEBOUNCE_MS)
        val updated = withContext(Dispatchers.IO) {
            musicFilePlayer.setVolume(controlsState.lullabyLevel)
        }
        if (!updated.ok) {
            musicStatus = updated.message
        }
    }

    val visibleAlerts = retainedAlerts
        .values
        .toList()
        .withoutAcknowledged(acknowledgedAlertIds)
        .sortedByDescending { it.timestampMillis }
    BoardAlertNotifications(
        alerts = visibleAlerts,
        notifiedAlertIds = notifiedAlertIds,
    )

    Scaffold(
        bottomBar = {
            if (!cameraState.fullscreen) {
                AppNavigation(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it },
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        if (cameraState.fullscreen) {
            FullscreenCameraView(
                streamUrl = cameraStreamUrl,
                snapshotUrl = cameraSnapshotUrl,
                cameraLive = cameraState.live,
                onCameraLiveChange = { cameraState.live = it },
                onExitFullscreen = { toggleFullscreen() },
            )
        } else {
            val backgroundBrush = AppBackgroundBrush()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundBrush)
                    .padding(paddingValues),
            ) {
                when (selectedTab) {
                        AppTab.Overview -> OverviewPage(
                            context = view.context,
                            uiState = overviewUiState(
                                environmentReading = environmentReading,
                                alerts = visibleAlerts,
                            ),
                            streamUrl = cameraStreamUrl,
                            snapshotUrl = cameraSnapshotUrl,
                            cameraLive = cameraState.live,
                            onCameraLiveChange = { cameraState.live = it },
                            listenEnabled = cameraState.listenEnabled,
                            onListenToggle = { toggleListen() },
                            voiceRecording = cameraState.voiceRecording,
                            onVoicePressStart = { startVoiceMessage() },
                            onVoicePressEnd = { finishVoiceMessage() },
                            onSnapshot = {
                                scope.launch {
                                    val imported = withContext(Dispatchers.IO) {
                                        boardApi.snapshotStream()?.let { stream ->
                                            albumStore.importMoment(
                                                capturedAtMillis = System.currentTimeMillis(),
                                                source = stream,
                                            )
                                        }
                                    }
                                    if (imported != null) {
                                        refreshAlbumMoments()
                                        showMessage("已保存到成长相册")
                                    } else {
                                        showMessage("抓拍保存失败，请检查相册文件夹和看护网络")
                                    }
                                }
                            },
                            onToggleFullscreen = { toggleFullscreen() },
                            onAcknowledge = { alertId ->
                                acknowledgedAlertIds = acknowledgedAlertIds + alertId
                                NotificationHelper.cancelBoardAlert(view.context, alertId)
                                showMessage("已处理警告")
                            },
                        )

                        AppTab.Sleep -> SleepPage()

                        AppTab.Controls -> ControlsPage(
                            musicVolume = controlsState.lullabyLevel,
                            onMusicVolumeChange = {
                                controlsState.lullabyLevel = it
                                if (musicPlaying) {
                                    musicStatus = "循环播放中 · 音量 ${(it * 100).toInt()}%"
                                }
                            },
                            musicEnabled = controlsState.lullabyEnabled,
                            onMusicEnabledChange = { enabled ->
                                controlsState.lullabyEnabled = enabled
                                if (!enabled) {
                                    if (musicPlaying) stopSelectedMusic()
                                } else if (!musicPlaying && selectedMusicTrack?.boardTrackId != null) {
                                    playSelectedMusic()
                                } else if (!musicStatusLoaded) {
                                    syncBoardLullaby()
                                }
                            },
                            selectedTrack = selectedMusicTrack,
                            playerStatus = musicStatus,
                            playerBusy = musicBusy,
                            playerPlaying = musicPlaying,
                            onPickTrack = { musicFileLauncher.launch(arrayOf("audio/*")) },
                            onPlayTrack = { playSelectedMusic() },
                            onStopTrack = { stopSelectedMusic() },
                        )

                        AppTab.Family -> FamilyPage(
                            moments = growthMoments,
                            imageLoader = albumImageLoader,
                            inviteCount = familySettingsState.inviteCount,
                            onInvite = {
                                familySettingsState.inviteCount += 1
                                showMessage("已生成第 ${familySettingsState.inviteCount} 个家人邀请链接")
                            },
                        )

                        AppTab.Settings -> SettingsPage(
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            faceAlert = alertSettingsState.faceAlert,
                            onFaceAlertChange = { alertSettingsState.faceAlert = it },
                            noPersonAlert = alertSettingsState.noPersonAlert,
                            onNoPersonAlertChange = { alertSettingsState.noPersonAlert = it },
                            cryAlert = alertSettingsState.cryAlert,
                            onCryAlertChange = { alertSettingsState.cryAlert = it },
                            boundaryAlert = alertSettingsState.boundaryAlert,
                            onBoundaryAlertChange = { alertSettingsState.boundaryAlert = it },
                            streamUrl = cameraStreamUrl,
                            snapshotUrl = cameraSnapshotUrl,
                            cameraLive = cameraState.live,
                            safetyZone = cameraState.safetyZone,
                            activityZoneMode = cameraState.activityZoneMode,
                            onSafetyZoneChange = {
                                val zone = it.normalized()
                                cameraState.safetyZone = zone
                                saveBoardActivityZone(zone, cameraState.activityZoneMode)
                            },
                            onActivityZoneModeChange = {
                                cameraState.activityZoneMode = it
                                cameraState.safetyZone?.let { zone ->
                                    saveBoardActivityZone(zone, it)
                                }
                            },
                            onResetSafetyZone = {
                                cameraState.safetyZone = null
                                clearBoardActivityZone()
                                showMessage("活动区域已重置")
                            },
                            albumFolderLabel = albumFolderLabel,
                            onAlbumFolderSelected = { uri ->
                                albumStore.setAlbumFolderUri(uri)
                                albumFolderLabel = albumStore.albumFolderLabel()
                                refreshAlbumMoments()
                                showMessage("成长相册存放文件夹已更新")
                            },
                            onAlbumSync = {
                                val result = albumSyncClient.syncBoardPhotos(albumStore)
                                refreshAlbumMoments()
                                if (result.importedCount > 0) {
                                    showMessage("已同步 ${result.importedCount} 张板端照片")
                                } else {
                                    showMessage("同步失败，请检查相册文件夹和看护网络")
                                }
                            },
                            boardConnection = boardConnection,
                        )
                    }
                }
            }
        }
    }
}

private const val OVERVIEW_REFRESH_INTERVAL_MS = 2_000L
private const val MUSIC_VOLUME_SYNC_DEBOUNCE_MS = 180L

private fun Map<BoardAlertType, BoardAlert>.withLatestAlerts(alerts: List<BoardAlert>): Map<BoardAlertType, BoardAlert> {
    if (alerts.isEmpty()) return this
    val next = toMutableMap()
    alerts.forEach { alert ->
        val previous = next[alert.type]
        if (previous == null || alert.timestampMillis >= previous.timestampMillis) {
            next[alert.type] = alert
        }
    }
    return next
}

@Composable
private fun BoardAlertNotifications(
    alerts: List<BoardAlert>,
    notifiedAlertIds: MutableSet<String>,
) {
    val context = LocalView.current.context
    LaunchedEffect(alerts.map { it.id }) {
        alerts
            .filterNot { it.id in notifiedAlertIds }
            .forEach { alert ->
                NotificationHelper.showBoardAlert(context, alert)
                notifiedAlertIds += alert.id
            }
    }
}
