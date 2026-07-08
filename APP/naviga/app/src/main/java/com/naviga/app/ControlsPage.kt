package com.naviga.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun ControlsPage(
    musicVolume: Float,
    onMusicVolumeChange: (Float) -> Unit,
    musicEnabled: Boolean,
    onMusicEnabledChange: (Boolean) -> Unit,
    selectedTrack: MusicTrack?,
    playerStatus: String,
    playerBusy: Boolean,
    playerPlaying: Boolean,
    onPickTrack: () -> Unit,
    onPlayTrack: () -> Unit,
    onStopTrack: () -> Unit,
) {
    PageColumn {
        item {
            PageHeader(title = "摇篮曲")
        }
        item {
            MusicDeckCard(
                track = selectedTrack,
                status = playerStatus,
                busy = playerBusy,
                playing = playerPlaying,
                musicEnabled = musicEnabled,
                volume = musicVolume,
                onVolumeChange = onMusicVolumeChange,
                onEnabledChange = onMusicEnabledChange,
                onPickTrack = onPickTrack,
                onPlayTrack = onPlayTrack,
                onStopTrack = onStopTrack,
            )
        }
    }
}

@Composable
private fun MusicDeckCard(
    track: MusicTrack?,
    status: String,
    busy: Boolean,
    playing: Boolean,
    musicEnabled: Boolean,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPickTrack: () -> Unit,
    onPlayTrack: () -> Unit,
    onStopTrack: () -> Unit,
) {
    PressedCard(contentPadding = PaddingValues(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(icon = Icons.Rounded.LibraryMusic)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "板端摇篮曲",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Switch(checked = musicEnabled, onCheckedChange = onEnabledChange)
        }

        TrackText(track = track)

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        PlayerStatus(status = status, playing = playing, busy = busy)

        PlayerControls(
            hasTrack = track != null,
            enabled = musicEnabled,
            busy = busy,
            playing = playing,
            onPickTrack = onPickTrack,
            onPlayTrack = onPlayTrack,
            onStopTrack = onStopTrack,
        )

        VolumeSection(
            volume = volume,
            enabled = musicEnabled,
            onVolumeChange = onVolumeChange,
        )
    }
}

@Composable
private fun TrackText(track: MusicTrack?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = track?.title ?: "未选择摇篮曲",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = track?.detailText() ?: "支持 MP3 / M4A / AAC / WAV",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlayerStatus(
    status: String,
    playing: Boolean,
    busy: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ControlShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (playing) Icons.Rounded.Repeat else Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = when {
                    playing -> MaterialTheme.colorScheme.primary
                    busy -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (playing) "$status · 循环" else status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlayerControls(
    hasTrack: Boolean,
    enabled: Boolean,
    busy: Boolean,
    playing: Boolean,
    onPickTrack: () -> Unit,
    onPlayTrack: () -> Unit,
    onStopTrack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick = onPickTrack,
            enabled = !busy,
            modifier = Modifier.weight(1f),
            shape = ControlShape,
            contentPadding = PaddingValues(14.dp),
        ) {
            Icon(Icons.Rounded.AudioFile, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("选摇篮曲")
        }
        Button(
            onClick = if (playing || busy) onStopTrack else onPlayTrack,
            enabled = if (playing || busy) true else enabled && hasTrack,
            modifier = Modifier.weight(1f),
            shape = ControlShape,
            contentPadding = PaddingValues(14.dp),
        ) {
            Icon(
                imageVector = when {
                    playing -> Icons.Rounded.Stop
                    busy -> Icons.Rounded.Pause
                    else -> Icons.Rounded.PlayArrow
                },
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(if (playing || busy) "停止" else "播放")
        }
    }
}

@Composable
private fun VolumeSection(
    volume: Float,
    enabled: Boolean,
    onVolumeChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.56f },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "板端音量",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(volume * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            enabled = enabled,
        )
    }
}

private fun MusicTrack.detailText(): String {
    val duration = durationMillis?.let { formatDuration(it) } ?: "--:--"
    val size = if (sizeBytes > 0L) formatBytes(sizeBytes) else "未知大小"
    return "$duration · $size · 循环播放"
}

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb)
    val kb = bytes / 1024.0
    return String.format(Locale.US, "%.0f KB", kb.coerceAtLeast(1.0))
}
