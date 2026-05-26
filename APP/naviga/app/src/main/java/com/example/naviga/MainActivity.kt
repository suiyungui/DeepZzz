package com.example.naviga

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.naviga.ui.theme.NavigaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NavigaRoot()
        }
    }
}

private enum class AppTab(
    val label: String,
    val icon: ImageVector,
) {
    Overview("总览", Icons.Rounded.Home),
    Sleep("睡眠", Icons.Rounded.Favorite),
    Alerts("告警", Icons.Rounded.Notifications),
    Family("家人", Icons.Rounded.Group),
}

private data class Metric(
    val label: String,
    val value: String,
    val hint: String,
    val icon: ImageVector,
)

private data class AlertEvent(
    val title: String,
    val detail: String,
    val time: String,
    val icon: ImageVector,
    val category: String,
    val active: Boolean,
    val urgent: Boolean = false,
)

private const val CameraBaseUrl = "http://192.168.22.193:8080/"

private enum class CameraStreamStatus {
    Connecting,
    Online,
    Offline,
}

private data class NetworkState(
    val available: Boolean,
    val refreshToken: Int,
)

@Composable
private fun rememberNetworkState(): NetworkState {
    val context = LocalContext.current
    var available by remember { mutableStateOf(isNetworkAvailable(context)) }
    var refreshToken by remember { mutableStateOf(0) }

    DisposableEffect(context) {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    available = isNetworkAvailable(context)
                    refreshToken += 1
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    available = isNetworkAvailable(context)
                    refreshToken += 1
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                mainHandler.post {
                    available = isNetworkAvailable(context)
                    refreshToken += 1
                }
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)
        available = isNetworkAvailable(context)

        onDispose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    return NetworkState(available = available, refreshToken = refreshToken)
}

private fun isNetworkAvailable(context: Context): Boolean {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@Composable
private fun NavigaRoot() {
    val systemDark = isSystemInDarkTheme()
    var darkTheme by rememberSaveable { mutableStateOf(systemDark) }

    NavigaTheme(darkTheme = darkTheme) {
        NavigaApp(
            darkTheme = darkTheme,
            onToggleTheme = { darkTheme = !darkTheme },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigaApp(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Overview) }
    var selectedCamera by rememberSaveable { mutableStateOf(0) }
    var cameraLive by rememberSaveable { mutableStateOf(true) }
    var talkEnabled by rememberSaveable { mutableStateOf(false) }
    var recording by rememberSaveable { mutableStateOf(false) }
    var snapshotCount by rememberSaveable { mutableStateOf(3) }
    var nightLightOn by rememberSaveable { mutableStateOf(true) }
    var lullabyOn by rememberSaveable { mutableStateOf(true) }
    var whiteNoiseOn by rememberSaveable { mutableStateOf(false) }
    var humidifierOn by rememberSaveable { mutableStateOf(false) }
    var lightLevel by rememberSaveable { mutableStateOf(0.34f) }
    var soundLevel by rememberSaveable { mutableStateOf(0.18f) }
    var faceAlert by rememberSaveable { mutableStateOf(true) }
    var cryAlert by rememberSaveable { mutableStateOf(true) }
    var coughAlert by rememberSaveable { mutableStateOf(true) }
    var zoneAlert by rememberSaveable { mutableStateOf(true) }
    var tempAlert by rememberSaveable { mutableStateOf(true) }
    var quietMode by rememberSaveable { mutableStateOf(false) }
    var momAccess by rememberSaveable { mutableStateOf(true) }
    var dadAccess by rememberSaveable { mutableStateOf(true) }
    var grandmaAccess by rememberSaveable { mutableStateOf(false) }
    var privacyMode by rememberSaveable { mutableStateOf(false) }
    var albumSync by rememberSaveable { mutableStateOf(true) }
    var reportShare by rememberSaveable { mutableStateOf(false) }
    var inviteCount by rememberSaveable { mutableStateOf(0) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            AppHeader(
                darkTheme = darkTheme,
                onToggleTheme = onToggleTheme,
            )
        },
        bottomBar = {
            AppNavigation(
                selectedTab = selectedTab,
                onSelect = { selectedTab = it },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(appBackgroundBrush())
                .padding(paddingValues),
        ) {
            Crossfade(
                targetState = selectedTab,
                label = "tabContentTransition",
            ) { tab ->
                when (tab) {
                AppTab.Overview -> OverviewPage(
                    selectedCamera = selectedCamera,
                    onCameraSelect = { selectedCamera = it },
                    cameraLive = cameraLive,
                    onCameraLiveChange = { cameraLive = it },
                    privacyMode = privacyMode,
                    talkEnabled = talkEnabled,
                    onTalkToggle = {
                        talkEnabled = !talkEnabled
                        showMessage(if (talkEnabled) "双向语音已开启" else "双向语音已关闭")
                    },
                    recording = recording,
                    onRecordingToggle = {
                        recording = !recording
                        showMessage(if (recording) "开始录制演示片段" else "录制已暂停")
                    },
                    onSnapshot = {
                        snapshotCount += 1
                        showMessage("已保存第 $snapshotCount 张成长瞬间")
                    },
                    nightLightOn = nightLightOn,
                    onNightLightChange = { nightLightOn = it },
                    lullabyOn = lullabyOn,
                    onLullabyChange = { lullabyOn = it },
                    quietMode = quietMode,
                    onQuietModeChange = { quietMode = it },
                    onSafetyCheck = { showMessage("安全自检完成：画面、声音、遮挡检测均正常") },
                )

                AppTab.Sleep -> SleepPage(
                    nightLightOn = nightLightOn,
                    onNightLightChange = { nightLightOn = it },
                    lullabyOn = lullabyOn,
                    onLullabyChange = { lullabyOn = it },
                    whiteNoiseOn = whiteNoiseOn,
                    onWhiteNoiseChange = { whiteNoiseOn = it },
                    humidifierOn = humidifierOn,
                    onHumidifierChange = { humidifierOn = it },
                    lightLevel = lightLevel,
                    onLightLevelChange = { lightLevel = it },
                    soundLevel = soundLevel,
                    onSoundLevelChange = { soundLevel = it },
                    onGenerateReport = { showMessage("已生成今晚睡眠报告预览") },
                )

                AppTab.Alerts -> AlertsPage(
                    faceAlert = faceAlert,
                    onFaceAlertChange = { faceAlert = it },
                    cryAlert = cryAlert,
                    onCryAlertChange = { cryAlert = it },
                    coughAlert = coughAlert,
                    onCoughAlertChange = { coughAlert = it },
                    zoneAlert = zoneAlert,
                    onZoneAlertChange = { zoneAlert = it },
                    tempAlert = tempAlert,
                    onTempAlertChange = { tempAlert = it },
                    quietMode = quietMode,
                    onQuietModeChange = { quietMode = it },
                    onAcknowledge = { showMessage("已将当前告警标记为已处理") },
                    onDrill = { showMessage("已模拟推送：妈妈、爸爸设备均收到通知") },
                )

                AppTab.Family -> FamilyPage(
                    momAccess = momAccess,
                    onMomAccessChange = { momAccess = it },
                    dadAccess = dadAccess,
                    onDadAccessChange = { dadAccess = it },
                    grandmaAccess = grandmaAccess,
                    onGrandmaAccessChange = { grandmaAccess = it },
                    privacyMode = privacyMode,
                    onPrivacyModeChange = { privacyMode = it },
                    albumSync = albumSync,
                    onAlbumSyncChange = { albumSync = it },
                    reportShare = reportShare,
                    onReportShareChange = { reportShare = it },
                    inviteCount = inviteCount,
                    onInvite = {
                        inviteCount += 1
                        showMessage("已生成第 $inviteCount 个家人邀请链接")
                    },
                    onExport = { showMessage("儿科摘要已生成，当前仅为演示数据") },
                )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppHeader(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
        ),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = MaterialTheme.colorScheme.primary,
                    tonalElevation = 4.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Column {
                    Text(
                        text = "眠屿看护",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "小禾 · 婴儿房在线",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onToggleTheme) {
                Icon(
                    imageVector = if (darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    contentDescription = "切换深色主题",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

@Composable
private fun AppNavigation(
    selectedTab: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 10.dp,
    ) {
        AppTab.entries.forEach { tab ->
            val selected = selectedTab == tab
            val iconScale by animateFloatAsState(
                targetValue = if (selected) 1.16f else 1f,
                label = "navIconScale",
            )
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier.graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
                    )
                },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun OverviewPage(
    selectedCamera: Int,
    onCameraSelect: (Int) -> Unit,
    cameraLive: Boolean,
    onCameraLiveChange: (Boolean) -> Unit,
    privacyMode: Boolean,
    talkEnabled: Boolean,
    onTalkToggle: () -> Unit,
    recording: Boolean,
    onRecordingToggle: () -> Unit,
    onSnapshot: () -> Unit,
    nightLightOn: Boolean,
    onNightLightChange: (Boolean) -> Unit,
    lullabyOn: Boolean,
    onLullabyChange: (Boolean) -> Unit,
    quietMode: Boolean,
    onQuietModeChange: (Boolean) -> Unit,
    onSafetyCheck: () -> Unit,
) {
    PageColumn {
        item {
            PageTitle(
                eyebrow = "Nursery command center",
                title = "总览",
                subtitle = "直播、环境、安全状态和快捷控制集中在这一页。",
            )
        }
        item {
            LiveCameraCard(
                selectedCamera = selectedCamera,
                onCameraSelect = onCameraSelect,
                cameraLive = cameraLive,
                privacyMode = privacyMode,
                talkEnabled = talkEnabled,
                recording = recording,
                onTalkToggle = onTalkToggle,
                onRecordingToggle = onRecordingToggle,
                onSnapshot = onSnapshot,
            )
        }
        item {
            MetricGrid(
                metrics = listOf(
                    Metric("呼吸节律", "31", "次/分 · 平稳", Icons.Rounded.Air),
                    Metric("房间声音", "28", "dB · 安静", Icons.AutoMirrored.Rounded.VolumeUp),
                    Metric("环境温度", "22.8", "℃ · 舒适", Icons.Rounded.Thermostat),
                    Metric("湿度", "48", "% · 正常", Icons.Rounded.WaterDrop),
                ),
            )
        }
        item { SectionHeader(title = "快捷控制", action = "本地演示") }
        item {
            ControlPanel(
                controls = listOf(
                    ToggleSpec("实时摄像头", if (privacyMode) "隐私模式覆盖中" else "关闭后显示隐私占位画面", Icons.Rounded.Videocam, cameraLive, onCameraLiveChange),
                    ToggleSpec("夜灯", "当前为睡前渐暗模式", Icons.Rounded.Lightbulb, nightLightOn, onNightLightChange),
                    ToggleSpec("摇篮曲", "森林白噪音 · 音量 18%", Icons.AutoMirrored.Rounded.VolumeUp, lullabyOn, onLullabyChange),
                    ToggleSpec("安静时段", if (quietMode) "只推送高优先级告警" else "普通告警照常推送", Icons.Rounded.Notifications, quietMode, onQuietModeChange),
                ),
            )
        }
        item {
            Button(
                onClick = onSafetyCheck,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Icon(Icons.Rounded.Security, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("运行一次安全自检")
            }
        }
    }
}

@Composable
private fun SleepPage(
    nightLightOn: Boolean,
    onNightLightChange: (Boolean) -> Unit,
    lullabyOn: Boolean,
    onLullabyChange: (Boolean) -> Unit,
    whiteNoiseOn: Boolean,
    onWhiteNoiseChange: (Boolean) -> Unit,
    humidifierOn: Boolean,
    onHumidifierChange: (Boolean) -> Unit,
    lightLevel: Float,
    onLightLevelChange: (Float) -> Unit,
    soundLevel: Float,
    onSoundLevelChange: (Float) -> Unit,
    onGenerateReport: () -> Unit,
) {
    PageColumn {
        item {
            PageTitle(
                eyebrow = "Sleep studio",
                title = "睡眠",
                subtitle = "用假数据展示入睡流程、夜间报告和可调参数。",
            )
        }
        item {
            SleepScoreCard(onGenerateReport = onGenerateReport)
        }
        item { SectionHeader(title = "睡前照护", action = "全部可控") }
        item {
            ControlPanel(
                controls = listOf(
                    ToggleSpec("夜灯渐暗", "${(lightLevel * 100).toInt()}% 亮度", Icons.Rounded.Lightbulb, nightLightOn, onNightLightChange),
                    ToggleSpec("摇篮曲", "${(soundLevel * 100).toInt()}% 音量", Icons.AutoMirrored.Rounded.VolumeUp, lullabyOn, onLullabyChange),
                    ToggleSpec("白噪音", "雨声 · 40 分钟后停止", Icons.Rounded.Air, whiteNoiseOn, onWhiteNoiseChange),
                    ToggleSpec("加湿器", "目标湿度 50%", Icons.Rounded.WaterDrop, humidifierOn, onHumidifierChange),
                ),
            )
        }
        item {
            SliderCard(
                title = "夜灯亮度",
                detail = if (nightLightOn) "模拟调节婴儿房夜灯亮度" else "夜灯关闭，亮度暂不可调",
                icon = Icons.Rounded.LightMode,
                value = lightLevel,
                onValueChange = onLightLevelChange,
                enabled = nightLightOn,
            )
        }
        item {
            SliderCard(
                title = "安抚声音量",
                detail = if (lullabyOn || whiteNoiseOn) "模拟调节摇篮曲和白噪音音量" else "安抚声音已关闭，音量暂不可调",
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                value = soundLevel,
                onValueChange = onSoundLevelChange,
                enabled = lullabyOn || whiteNoiseOn,
            )
        }
    }
}

@Composable
private fun AlertsPage(
    faceAlert: Boolean,
    onFaceAlertChange: (Boolean) -> Unit,
    cryAlert: Boolean,
    onCryAlertChange: (Boolean) -> Unit,
    coughAlert: Boolean,
    onCoughAlertChange: (Boolean) -> Unit,
    zoneAlert: Boolean,
    onZoneAlertChange: (Boolean) -> Unit,
    tempAlert: Boolean,
    onTempAlertChange: (Boolean) -> Unit,
    quietMode: Boolean,
    onQuietModeChange: (Boolean) -> Unit,
    onAcknowledge: () -> Unit,
    onDrill: () -> Unit,
) {
    var selectedFilter by rememberSaveable { mutableStateOf("全部") }
    val events = listOf(
        AlertEvent("脸部无遮挡", "上一轮遮挡检测通过，床品边缘距离安全。", "刚刚", Icons.Rounded.CheckCircle, "安全", faceAlert),
        AlertEvent("短促哭声", "持续 11 秒后自行安静，未触发高优先级通知。", "5 分钟", Icons.AutoMirrored.Rounded.VolumeUp, "声音", cryAlert),
        AlertEvent("疑似咳嗽 2 次", "已保存 18 秒片段，可加入儿科记录。", "8 分钟", Icons.Rounded.Notifications, "声音", coughAlert),
        AlertEvent("危险区域接近", "身体靠近床边，建议查看实时画面。", "21 分钟", Icons.Rounded.Warning, "安全", zoneAlert, urgent = true),
        AlertEvent("温湿度波动", "室温上升 0.8℃，仍在舒适范围内。", "32 分钟", Icons.Rounded.Thermostat, "环境", tempAlert),
    )
    val filteredEvents = events.filter { selectedFilter == "全部" || it.category == selectedFilter }

    PageColumn {
        item {
            PageTitle(
                eyebrow = "Alert rules",
                title = "告警",
                subtitle = "每条规则都能切换。告警列表为假数据，可模拟处理和推送演练。",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "安全", "声音", "环境").forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter) },
                    )
                }
            }
        }
        item {
            ControlPanel(
                controls = listOf(
                    ToggleSpec("遮脸检测", "检测脸部、口鼻和床品遮挡", Icons.Rounded.Security, faceAlert, onFaceAlertChange),
                    ToggleSpec("哭声检测", "高置信度哭声才通知", Icons.AutoMirrored.Rounded.VolumeUp, cryAlert, onCryAlertChange),
                    ToggleSpec("咳嗽检测", "保存短片段供复核", Icons.Rounded.Assessment, coughAlert, onCoughAlertChange),
                    ToggleSpec("危险区域", "靠近床沿和翻身风险提醒", Icons.Rounded.Warning, zoneAlert, onZoneAlertChange),
                    ToggleSpec("温湿提醒", "温度和湿度超阈值提醒", Icons.Rounded.Thermostat, tempAlert, onTempAlertChange),
                    ToggleSpec("安静时段", "夜间仅保留高优先级推送", Icons.Rounded.Notifications, quietMode, onQuietModeChange),
                ),
            )
        }
        item { SectionHeader(title = "最近告警", action = "$selectedFilter 筛选") }
        items(filteredEvents.size) { index ->
            AlertEventCard(
                event = filteredEvents[index],
                onAcknowledge = onAcknowledge,
            )
        }
        item {
            Button(
                onClick = onDrill,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                contentPadding = PaddingValues(16.dp),
            ) {
                Icon(Icons.Rounded.Notifications, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("模拟一次告警推送")
            }
        }
    }
}

@Composable
private fun FamilyPage(
    momAccess: Boolean,
    onMomAccessChange: (Boolean) -> Unit,
    dadAccess: Boolean,
    onDadAccessChange: (Boolean) -> Unit,
    grandmaAccess: Boolean,
    onGrandmaAccessChange: (Boolean) -> Unit,
    privacyMode: Boolean,
    onPrivacyModeChange: (Boolean) -> Unit,
    albumSync: Boolean,
    onAlbumSyncChange: (Boolean) -> Unit,
    reportShare: Boolean,
    onReportShareChange: (Boolean) -> Unit,
    inviteCount: Int,
    onInvite: () -> Unit,
    onExport: () -> Unit,
) {
    PageColumn {
        item {
            PageTitle(
                eyebrow = "Family circle",
                title = "家人",
                subtitle = "展示多人权限、相册同步和隐私模式。所有开关均为本地状态。",
            )
        }
        item {
            FamilySummaryCard(
                inviteCount = inviteCount,
                onInvite = onInvite,
            )
        }
        item { SectionHeader(title = "访问权限", action = "三位家人") }
        item {
            ControlPanel(
                controls = listOf(
                    ToggleSpec("妈妈", "可看直播、回放、告警和相册", Icons.Rounded.Group, momAccess, onMomAccessChange),
                    ToggleSpec("爸爸", "可看直播并接收声音告警", Icons.Rounded.Group, dadAccess, onDadAccessChange),
                    ToggleSpec("外婆", "仅开放成长相册和日报", Icons.Rounded.Image, grandmaAccess, onGrandmaAccessChange),
                ),
            )
        }
        item {
            ControlPanel(
                controls = listOf(
                    ToggleSpec("隐私模式", "开启后摄像头画面以占位状态展示", Icons.Rounded.Lock, privacyMode, onPrivacyModeChange),
                    ToggleSpec("相册同步", "每日自动精选成长瞬间", Icons.Rounded.CloudUpload, albumSync, onAlbumSyncChange),
                    ToggleSpec("儿科摘要", "允许导出睡眠与咳嗽摘要", Icons.Rounded.Assessment, reportShare, onReportShareChange),
                ),
            )
        }
        item {
            AlbumPreviewCard(onExport = onExport)
        }
    }
}

@Composable
private fun PageColumn(
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun PageTitle(
    eyebrow: String,
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = eyebrow.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = action,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun LiveCameraCard(
    selectedCamera: Int,
    onCameraSelect: (Int) -> Unit,
    cameraLive: Boolean,
    privacyMode: Boolean,
    talkEnabled: Boolean,
    recording: Boolean,
    onTalkToggle: () -> Unit,
    onRecordingToggle: () -> Unit,
    onSnapshot: () -> Unit,
) {
    val cameraNames = listOf("婴儿床", "游戏垫", "门口")
    val effectiveLive = cameraLive && !privacyMode
    val streamVisible = effectiveLive && selectedCamera == 0
    val networkState = rememberNetworkState()
    val networkAvailable = networkState.available
    var manualReloadToken by remember { mutableStateOf(0) }
    val reloadToken = networkState.refreshToken + manualReloadToken
    var streamStatus by remember { mutableStateOf(CameraStreamStatus.Connecting) }

    LaunchedEffect(streamVisible, reloadToken, networkAvailable) {
        streamStatus = when {
            !streamVisible -> CameraStreamStatus.Offline
            networkAvailable -> CameraStreamStatus.Connecting
            else -> CameraStreamStatus.Offline
        }
    }

    val liveText = when {
        privacyMode -> "隐私模式"
        streamVisible && streamStatus == CameraStreamStatus.Online -> "实时看护"
        streamVisible && streamStatus == CameraStreamStatus.Connecting -> "连接中"
        cameraLive -> "摄像头离线"
        else -> "摄像头关闭"
    }
    val footerText = when {
        privacyMode -> "隐私模式已开启，直播画面已隐藏"
        streamVisible && !networkAvailable -> "网络已断开，恢复后自动重连"
        streamVisible && streamStatus == CameraStreamStatus.Online -> "开发板摄像头在线 · 画面同步中"
        streamVisible && streamStatus == CameraStreamStatus.Connecting -> "正在连接开发板摄像头"
        streamVisible -> "摄像头流不可用，可手动重连"
        cameraLive -> "当前摄像头暂无接入"
        else -> "摄像头已关闭，保留环境监测"
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary,
                                MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ),
                    ),
            ) {
                BabyMonitorGraphic(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(210.dp),
                    muted = !effectiveLive || (streamVisible && streamStatus != CameraStreamStatus.Online),
                )

                if (streamVisible && networkAvailable) {
                    CameraStreamView(
                        modifier = Modifier.fillMaxSize(),
                        reloadToken = reloadToken,
                        onStatusChange = { streamStatus = it },
                        visible = streamStatus == CameraStreamStatus.Online,
                    )
                }
                StatusBadge(
                    text = liveText,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(14.dp),
                )
                StatusBadge(
                    text = "22.8℃ · 48%",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp),
                )
                Text(
                    text = footerText,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cameraNames.forEachIndexed { index, name ->
                    FilterChip(
                        selected = selectedCamera == index,
                        onClick = { onCameraSelect(index) },
                        label = { Text(name) },
                        enabled = cameraLive,
                    )
                }
            }

            CameraHealthStrip(
                status = streamStatus,
                networkAvailable = networkAvailable,
                streamVisible = streamVisible,
                privacyMode = privacyMode,
                selectedCamera = cameraNames[selectedCamera],
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniActionButton(
                    label = if (talkEnabled) "停止语音" else "双向语音",
                    icon = if (talkEnabled) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    selected = talkEnabled,
                    onClick = onTalkToggle,
                    enabled = effectiveLive,
                    modifier = Modifier.weight(1f),
                )
                MiniActionButton(
                    label = if (recording) "暂停录制" else "录制",
                    icon = if (recording) Icons.Rounded.StopCircle else Icons.Rounded.FiberManualRecord,
                    selected = recording,
                    onClick = onRecordingToggle,
                    enabled = effectiveLive,
                    modifier = Modifier.weight(1f),
                )
                MiniActionButton(
                    label = if (streamVisible && streamStatus != CameraStreamStatus.Online) "重连" else "抓拍",
                    icon = if (streamVisible && streamStatus != CameraStreamStatus.Online) Icons.Rounded.Refresh else Icons.Rounded.PhotoCamera,
                    selected = false,
                    onClick = {
                        if (streamVisible && streamStatus != CameraStreamStatus.Online) {
                            manualReloadToken += 1
                        } else {
                            onSnapshot()
                        }
                    },
                    enabled = effectiveLive,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CameraStreamView(
    modifier: Modifier = Modifier,
    reloadToken: Int,
    onStatusChange: (CameraStreamStatus) -> Unit,
    visible: Boolean,
) {
    val context = LocalContext.current
    val streamUrl = remember(reloadToken) { "${CameraBaseUrl}hls/stream.m3u8?t=$reloadToken" }
    val exoPlayer = remember(reloadToken) {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> onStatusChange(CameraStreamStatus.Online)
                    Player.STATE_BUFFERING, Player.STATE_IDLE -> onStatusChange(CameraStreamStatus.Connecting)
                    Player.STATE_ENDED -> onStatusChange(CameraStreamStatus.Offline)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onStatusChange(CameraStreamStatus.Offline)
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                if (isLoading) {
                    onStatusChange(CameraStreamStatus.Connecting)
                }
            }
        }
        exoPlayer.addListener(listener)
        onStatusChange(CameraStreamStatus.Connecting)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    AndroidView(
        modifier = modifier.graphicsLayer {
            alpha = if (visible) 1f else 0f
        },
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                useController = false
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                player = exoPlayer
            }
        },
        update = { playerView ->
            playerView.player = exoPlayer
        },
    )
}

@Composable
private fun CameraHealthStrip(
    status: CameraStreamStatus,
    networkAvailable: Boolean,
    streamVisible: Boolean,
    privacyMode: Boolean,
    selectedCamera: String,
) {
    val online = streamVisible && networkAvailable && status == CameraStreamStatus.Online
    val stateColor = when {
        privacyMode -> MaterialTheme.colorScheme.secondary
        streamVisible && networkAvailable && status == CameraStreamStatus.Connecting -> MaterialTheme.colorScheme.tertiary
        online -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val detailText = when {
        privacyMode -> "直播隐藏 · 传感器仍在线"
        !streamVisible -> "当前摄像头暂无视频源"
        !networkAvailable -> "网络断开 · 恢复后自动刷新"
        online -> "局域网在线 · 摄像头流正常"
        status == CameraStreamStatus.Connecting -> "正在连接开发板视频流"
        else -> "摄像头流离线 · 点击重连"
    }
    val badgeText = when {
        online -> "在线"
        streamVisible && networkAvailable && status == CameraStreamStatus.Connecting -> "连接中"
        else -> "离线"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = selectedCamera,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = detailText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = stateColor.copy(alpha = 0.14f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (online) Icons.Rounded.CheckCircle else Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = stateColor,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelLarge,
                    color = stateColor,
                )
            }
        }
    }
}

@Composable
private fun BabyMonitorGraphic(
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val alpha = if (muted) 0.28f else 0.72f
        drawOval(
            color = Color.White.copy(alpha = alpha),
            topLeft = Offset(size.width * 0.18f, size.height * 0.25f),
            size = Size(size.width * 0.64f, size.height * 0.48f),
        )
        drawCircle(
            color = Color(0xFFF6D6C6).copy(alpha = alpha),
            radius = size.minDimension * 0.2f,
            center = Offset(size.width * 0.5f, size.height * 0.45f),
        )
        drawCircle(
            color = Color(0xFF183F37).copy(alpha = alpha),
            radius = size.minDimension * 0.018f,
            center = Offset(size.width * 0.43f, size.height * 0.43f),
        )
        drawCircle(
            color = Color(0xFF183F37).copy(alpha = alpha),
            radius = size.minDimension * 0.018f,
            center = Offset(size.width * 0.57f, size.height * 0.43f),
        )
        drawArc(
            color = Color.White.copy(alpha = alpha),
            startAngle = 205f,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = Offset(size.width * 0.13f, size.height * 0.16f),
            size = Size(size.width * 0.74f, size.height * 0.74f),
            style = Stroke(width = size.minDimension * 0.045f, cap = StrokeCap.Round),
        )
        drawCircle(
            color = Color(0xFFD99B34).copy(alpha = if (muted) 0.35f else 1f),
            radius = size.minDimension * 0.08f,
            center = Offset(size.width * 0.72f, size.height * 0.24f),
        )
    }
}

@Composable
private fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.18f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

@Composable
private fun MetricGrid(metrics: List<Metric>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        metrics.chunked(2).forEach { rowMetrics ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowMetrics.forEach { metric ->
                    MetricCard(metric = metric, modifier = Modifier.weight(1f))
                }
                if (rowMetrics.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    metric: Metric,
    modifier: Modifier = Modifier,
) {
    PressedCard(modifier = modifier) {
        IconBadge(icon = metric.icon)
        Spacer(Modifier.height(14.dp))
        Text(
            text = metric.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = metric.value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = metric.hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

private data class ToggleSpec(
    val title: String,
    val detail: String,
    val icon: ImageVector,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
)

@Composable
private fun ControlPanel(controls: List<ToggleSpec>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        controls.forEach { control ->
            ToggleRow(control = control)
        }
    }
}

@Composable
private fun ToggleRow(control: ToggleSpec) {
    PressedCard(
        onClick = { control.onCheckedChange(!control.checked) },
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(icon = control.icon)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = control.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = control.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = control.checked,
                onCheckedChange = control.onCheckedChange,
            )
        }
    }
}

@Composable
private fun SleepScoreCard(onGenerateReport: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { 0.82f },
                        modifier = Modifier.size(112.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 10.dp,
                        trackColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("82", style = MaterialTheme.typography.headlineSmall)
                        Text("分", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("深睡占比提升", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "21:24 入睡，05:46 醒来。记录 4 次轻微体动、1 次短醒。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AssistChip(
                        onClick = onGenerateReport,
                        label = { Text("生成睡眠报告") },
                        leadingIcon = { Icon(Icons.Rounded.Assessment, contentDescription = null) },
                    )
                }
            }
            SleepTimeline()
        }
    }
}

@Composable
private fun SleepTimeline() {
    val bars = listOf(
        0.32f to MaterialTheme.colorScheme.tertiary,
        0.88f to MaterialTheme.colorScheme.primary,
        0.78f to MaterialTheme.colorScheme.primary,
        0.55f to MaterialTheme.colorScheme.tertiary,
        0.24f to MaterialTheme.colorScheme.secondary,
        0.94f to MaterialTheme.colorScheme.primary,
        0.84f to MaterialTheme.colorScheme.primary,
        0.62f to MaterialTheme.colorScheme.tertiary,
        0.72f to MaterialTheme.colorScheme.primary,
        0.44f to MaterialTheme.colorScheme.tertiary,
        0.26f to MaterialTheme.colorScheme.secondary,
        0.38f to MaterialTheme.colorScheme.tertiary,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEach { (height, color) ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((48 * height).dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
    }
}

@Composable
private fun SliderCard(
    title: String,
    detail: String,
    icon: ImageVector,
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    PressedCard(
        modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.62f },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(icon = icon)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "$detail · ${(value * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun AlertEventCard(
    event: AlertEvent,
    onAcknowledge: () -> Unit,
) {
    val iconColor = when {
        !event.active -> MaterialTheme.colorScheme.onSurfaceVariant
        event.urgent -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    PressedCard(
        modifier = Modifier.graphicsLayer { alpha = if (event.active) 1f else 0.62f },
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(icon = event.icon, tint = iconColor)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(text = event.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${event.category} · ${event.time}${if (event.active) "" else " · 规则已暂停"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            IconButton(
                onClick = onAcknowledge,
                enabled = event.active,
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

@Composable
private fun FamilySummaryCard(
    inviteCount: Int,
    onInvite: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(label = "妈", color = MaterialTheme.colorScheme.primaryContainer)
                Avatar(label = "爸", color = MaterialTheme.colorScheme.secondaryContainer)
                Avatar(label = "外", color = MaterialTheme.colorScheme.tertiaryContainer)
                Column(modifier = Modifier.weight(1f)) {
                    Text("3 位家人协作", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "妈妈在线查看直播，爸爸接收声音告警，外婆暂未开放直播。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onInvite,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(15.dp),
            ) {
                Icon(Icons.Rounded.PersonAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (inviteCount == 0) "邀请家人" else "再生成邀请链接")
            }
        }
    }
}

@Composable
private fun AlbumPreviewCard(onExport: () -> Unit) {
    PressedCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("成长相册", style = MaterialTheme.typography.titleLarge)
                Text(
                    "今日精选 5 个瞬间，已同步 3 个。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconBadge(icon = Icons.Rounded.Image)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            when (index) {
                                0 -> MaterialTheme.colorScheme.primaryContainer
                                1 -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.tertiaryContainer
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (index == 0) Icons.Rounded.CameraAlt else Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
        ) {
            Icon(Icons.Rounded.Assessment, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("导出演示儿科摘要")
        }
    }
}

@Composable
private fun Avatar(
    label: String,
    color: Color,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MiniActionButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "miniActionBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "miniActionContent",
    )
    Surface(
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.46f }
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = background,
        tonalElevation = if (selected) 6.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = contentColor)
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun IconBadge(
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        modifier = Modifier.size(42.dp),
        shape = RoundedCornerShape(15.dp),
        color = tint.copy(alpha = 0.14f),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun PressedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        label = "pressedCardScale",
    )
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(clickableModifier)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                shape = RoundedCornerShape(24.dp),
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun appBackgroundBrush(): Brush {
    return Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            MaterialTheme.colorScheme.background,
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun NavigaPreview() {
    NavigaTheme(darkTheme = false) {
        NavigaApp(
            darkTheme = false,
            onToggleTheme = {},
        )
    }
}
