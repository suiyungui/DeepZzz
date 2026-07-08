package com.naviga.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class CameraUiState {
    var live by mutableStateOf(true)
    var listenEnabled by mutableStateOf(false)
    var voiceRecording by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    var safetyZone by mutableStateOf<SafetyZone?>(null)
    var activityZoneMode by mutableStateOf(ActivityZoneMode.Safe)
}

class ControlsUiState {
    var lullabyLevel by mutableFloatStateOf(0.6f)
    var lullabyEnabled by mutableStateOf(true)
}

class AlertSettingsState {
    var faceAlert by mutableStateOf(true)
    var noPersonAlert by mutableStateOf(true)
    var cryAlert by mutableStateOf(true)
    var boundaryAlert by mutableStateOf(true)
}

fun AlertSettingsState.snapshot(
    safetyZone: SafetyZone? = null,
    activityZoneMode: ActivityZoneMode = ActivityZoneMode.Safe,
): AlertSettingsSnapshot {
    return AlertSettingsSnapshot(
        faceAlert = faceAlert,
        noPersonAlert = noPersonAlert,
        cryAlert = cryAlert,
        boundaryAlert = boundaryAlert,
        safetyZone = safetyZone?.normalized(),
        activityZoneMode = activityZoneMode,
    )
}

enum class ActivityZoneMode(val label: String, val apiValue: String) {
    Safe("安全区域", "safe"),
    Danger("危险区域", "danger"),
}

data class SafetyZone(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun normalized(): SafetyZone {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        return SafetyZone(
            left = minOf(l, r),
            top = minOf(t, b),
            right = maxOf(l, r),
            bottom = maxOf(t, b),
        )
    }

    fun contains(x: Float, y: Float): Boolean {
        val zone = normalized()
        return x in zone.left..zone.right && y in zone.top..zone.bottom
    }

    companion object {
        fun default() = SafetyZone(0.12f, 0.14f, 0.88f, 0.88f)
    }
}

class FamilySettingsState {
    var inviteCount by mutableIntStateOf(0)
}

@Composable
fun rememberCameraUiState(): CameraUiState {
    return rememberSaveable(saver = CameraUiStateSaver) { CameraUiState() }
}

@Composable
fun rememberControlsUiState(): ControlsUiState {
    return rememberSaveable(saver = ControlsUiStateSaver) { ControlsUiState() }
}

@Composable
fun rememberAlertSettingsState(): AlertSettingsState {
    return rememberSaveable(saver = AlertSettingsStateSaver) { AlertSettingsState() }
}

@Composable
fun rememberFamilySettingsState(): FamilySettingsState {
    return rememberSaveable(saver = FamilySettingsStateSaver) { FamilySettingsState() }
}

private val CameraUiStateSaver = listSaver<CameraUiState, Any>(
    save = {
        listOf(
            it.live,
            it.listenEnabled,
            it.fullscreen,
            it.safetyZone != null,
            it.safetyZone?.left ?: 0f,
            it.safetyZone?.top ?: 0f,
            it.safetyZone?.right ?: 0f,
            it.safetyZone?.bottom ?: 0f,
            it.activityZoneMode.name,
        )
    },
    restore = {
        CameraUiState().apply {
            live = it[0] as Boolean
            listenEnabled = it[1] as Boolean
            fullscreen = it[2] as Boolean
            val hasZone = it.getOrNull(3) as? Boolean ?: false
            safetyZone = if (hasZone) {
                SafetyZone(
                    left = it.getOrNull(4) as? Float ?: 0f,
                    top = it.getOrNull(5) as? Float ?: 0f,
                    right = it.getOrNull(6) as? Float ?: 0f,
                    bottom = it.getOrNull(7) as? Float ?: 0f,
                ).normalized()
            } else {
                null
            }
            activityZoneMode = (it.getOrNull(8) as? String)
                ?.let { name -> runCatching { ActivityZoneMode.valueOf(name) }.getOrNull() }
                ?: ActivityZoneMode.Safe
        }
    },
)

private val ControlsUiStateSaver = listSaver<ControlsUiState, Any>(
    save = { listOf(it.lullabyLevel, it.lullabyEnabled) },
    restore = {
        ControlsUiState().apply {
            lullabyLevel = it.getOrNull(0) as? Float ?: 0.6f
            lullabyEnabled = it.getOrNull(1) as? Boolean ?: true
        }
    },
)

private val AlertSettingsStateSaver = listSaver<AlertSettingsState, Any>(
    save = {
        listOf(
            it.faceAlert,
            it.noPersonAlert,
            it.cryAlert,
            it.boundaryAlert,
        )
    },
    restore = {
        AlertSettingsState().apply {
            faceAlert = it.getOrNull(0) as? Boolean ?: true
            noPersonAlert = if (it.size >= 3) it.getOrNull(1) as? Boolean ?: true else true
            cryAlert = if (it.size >= 3) {
                it.getOrNull(2) as? Boolean ?: true
            } else {
                it.getOrNull(1) as? Boolean ?: true
            }
            boundaryAlert = it.getOrNull(3) as? Boolean ?: true
        }
    },
)

private val FamilySettingsStateSaver = listSaver<FamilySettingsState, Any>(
    save = { listOf(it.inviteCount) },
    restore = {
        FamilySettingsState().apply {
            inviteCount = it[0] as Int
        }
    },
)
