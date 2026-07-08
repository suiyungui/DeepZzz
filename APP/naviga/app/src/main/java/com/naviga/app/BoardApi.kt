package com.naviga.app

import java.io.InputStream
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class BoardPhoto(
    val id: String,
    val capturedAtMillis: Long,
    val path: String,
)

data class BoardLullabyTrack(
    val id: String,
    val title: String,
    val durationMillis: Long?,
    val sizeBytes: Long,
    val updatedAtMillis: Long,
)

data class BoardLullabyStatus(
    val tracks: List<BoardLullabyTrack>,
    val currentTrack: BoardLullabyTrack?,
    val playing: Boolean,
    val volume: Float?,
)

data class BoardLullabyUploadResult(
    val track: BoardLullabyTrack?,
    val playing: Boolean,
)

data class BoardActivityZone(
    val zone: SafetyZone?,
    val mode: ActivityZoneMode,
)

interface BoardApi {
    fun health(): Boolean

    fun environment(): EnvironmentReading?

    fun cameraStreamUrl(): String

    fun snapshotUrl(): String

    fun snapshotStream(): InputStream?

    fun playTalkAudio(wavBytes: ByteArray): Boolean

    fun playMusicAudio(wavBytes: ByteArray, volume: Float): Boolean

    fun uploadLullaby(wavBytes: ByteArray, title: String, volume: Float): BoardLullabyUploadResult?

    fun playStoredLullaby(trackId: String?, volume: Float): Boolean

    fun lullabyStatus(): BoardLullabyStatus?

    fun stopMusicAudio(): Boolean

    fun setMusicVolume(volume: Float): Boolean

    fun startTalkAudio(): Boolean

    fun sendTalkPcm(pcmBytes: ByteArray): Boolean

    fun stopTalkAudio(): Boolean

    fun liveAudioStream(): InputStream?

    fun alerts(settings: AlertSettingsSnapshot): List<BoardAlert>

    fun activityZone(): BoardActivityZone?

    fun setActivityZone(zone: SafetyZone, mode: ActivityZoneMode): Boolean

    fun clearActivityZone(): Boolean

    fun photos(): List<BoardPhoto>

    fun photoStream(photo: BoardPhoto): InputStream?
}

class LanBoardApi(
    private val client: BoardRequestClient,
) : BoardApi {
    override fun health(): Boolean {
        return runCatching {
            val response = client.get("/api/healthz")
            response.ok && JSONObject(response.body).optBoolean("ok", false)
        }.getOrDefault(false)
    }

    override fun environment(): EnvironmentReading? {
        return runCatching {
            val response = client.get("/api/status")
            if (!response.ok) return@runCatching null
            val json = JSONObject(response.body)
            if (!json.optBoolean("ok", false)) return@runCatching null
            val yamnet = json.optJSONObject("yamnet")
            val temperatureHumidity = json.optJSONObject("temperature_humidity")
                ?: return@runCatching null
            EnvironmentReading(
                soundDecibels = yamnet?.optDouble("noise_db", 0.0)?.toFloat() ?: 0.0f,
                temperatureCelsius = temperatureHumidity.optDouble("temperature_c", 0.0).toFloat(),
                humidityPercent = temperatureHumidity.optDouble("humidity_percent", 0.0).toFloat(),
            )
        }.getOrNull()
    }

    override fun cameraStreamUrl(): String {
        return client.url("/hls/stream.m3u8?t=${System.currentTimeMillis()}")
    }

    override fun snapshotUrl(): String {
        return client.url("/api/preview-snapshot")
    }

    override fun snapshotStream(): InputStream? {
        return runCatching { client.getStream("/api/preview-snapshot") }.getOrNull()
    }

    override fun playTalkAudio(wavBytes: ByteArray): Boolean {
        return runCatching {
            val response = client.post("/api/audio/playback/upload", wavBytes, "audio/wav")
            response.ok && JSONObject(response.body).optBoolean("ok", false)
        }.getOrDefault(false)
    }

    override fun playMusicAudio(wavBytes: ByteArray, volume: Float): Boolean {
        val clampedVolume = volume.coerceIn(0f, 1f)
        return runCatching {
            val response = client.post(
                "/api/audio/playback/upload?volume=$clampedVolume&loop=1",
                wavBytes,
                "audio/wav",
                MUSIC_UPLOAD_TIMEOUT_MS,
            )
            response.ok && JSONObject(response.body).optBoolean("ok", false)
        }.getOrDefault(false)
    }

    override fun uploadLullaby(wavBytes: ByteArray, title: String, volume: Float): BoardLullabyUploadResult? {
        val clampedVolume = volume.coerceIn(0f, 1f)
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        return runCatching {
            val response = client.post(
                "/api/lullabies/upload?volume=$clampedVolume&title=$encodedTitle",
                wavBytes,
                "audio/wav",
                MUSIC_UPLOAD_TIMEOUT_MS,
            )
            if (!response.ok) return@runCatching null
            val json = JSONObject(response.body)
            if (!json.optBoolean("ok", false)) return@runCatching null
            val result = json.optJSONObject("result") ?: return@runCatching null
            BoardLullabyUploadResult(
                track = result.optJSONObject("track")?.toLullabyTrack(),
                playing = result.optJSONObject("playback")?.optString("status") == "playing",
            )
        }.getOrNull()
    }

    override fun playStoredLullaby(trackId: String?, volume: Float): Boolean {
        val clampedVolume = volume.coerceIn(0f, 1f)
        return runCatching {
            val body = JSONObject().put("volume", clampedVolume)
            if (!trackId.isNullOrBlank()) body.put("track_id", trackId)
            val response = client.post(
                "/api/lullabies/play",
                body.toString().toByteArray(Charsets.UTF_8),
                "application/json; charset=utf-8",
            )
            response.ok && JSONObject(response.body).optBoolean("ok", false)
        }.getOrDefault(false)
    }

    override fun lullabyStatus(): BoardLullabyStatus? {
        return runCatching {
            val response = client.get("/api/lullabies")
            if (!response.ok) return@runCatching null
            val json = JSONObject(response.body)
            if (!json.optBoolean("ok", false)) return@runCatching null
            json.optJSONObject("result")?.toLullabyStatus()
        }.getOrNull()
    }

    override fun setMusicVolume(volume: Float): Boolean {
        val clampedVolume = volume.coerceIn(0f, 1f)
        return runCatching {
            val response = client.post(
                "/api/audio/playback/volume",
                JSONObject().put("volume", clampedVolume).toString().toByteArray(Charsets.UTF_8),
                "application/json; charset=utf-8",
            )
            response.ok && JSONObject(response.body).optBoolean("ok", false)
        }.getOrDefault(false)
    }

    override fun stopMusicAudio(): Boolean {
        return runCatching {
            val response = client.post("/api/audio/playback/stop", ByteArray(0), "application/octet-stream")
            response.ok && JSONObject(response.body).optBoolean("ok", false)
        }.getOrDefault(false)
    }

    override fun startTalkAudio(): Boolean {
        return runCatching {
            val response = client.post("/api/audio/talk/start", ByteArray(0), "application/octet-stream")
            response.ok && JSONObject(response.body).optBoolean("ok", false)
        }.getOrDefault(false)
    }

    override fun sendTalkPcm(pcmBytes: ByteArray): Boolean {
        if (pcmBytes.isEmpty()) return true
        return runCatching {
            val response = client.post(
                "/api/audio/talk/chunk",
                pcmBytes,
                "application/octet-stream",
                TALK_CHUNK_TIMEOUT_MS,
            )
            response.ok && JSONObject(response.body).optBoolean("ok", false)
        }.getOrDefault(false)
    }

    override fun stopTalkAudio(): Boolean {
        return runCatching {
            val response = client.post("/api/audio/talk/stop", ByteArray(0), "application/octet-stream")
            response.ok && JSONObject(response.body).optBoolean("ok", false)
        }.getOrDefault(false)
    }

    override fun liveAudioStream(): InputStream? {
        return runCatching { client.getStream("/api/audio/live.pcm") }.getOrNull()
    }

    override fun alerts(settings: AlertSettingsSnapshot): List<BoardAlert> {
        return runCatching {
            val response = client.get("/api/status")
            if (!response.ok) return@runCatching emptyList()
            val json = JSONObject(response.body)
            if (!json.optBoolean("ok", false)) return@runCatching emptyList()
            val yamnet = json.optJSONObject("yamnet")
            val vision = json.optJSONObject("vision")
            val visionSafetyJson = json.optJSONObject("vision_safety")
                ?: vision?.optJSONObject("safety")
            val visionSafety = visionSafetyJson?.toVisionSafetyState()
                ?: visionSafetyFromLatest(vision?.optJSONObject("latest"))
            buildList {
                if (settings.cryAlert && yamnet?.optBoolean("crying", false) == true) {
                    val updatedAtSeconds = yamnet.optDouble("updated_at", 0.0)
                    val timestampMillis = if (updatedAtSeconds > 0.0) {
                        (updatedAtSeconds * 1_000).toLong()
                    } else {
                        System.currentTimeMillis()
                    }
                    add(
                        BoardAlert(
                            id = "cry-${timestampMillis / 10_000L}",
                            type = BoardAlertType.Crying,
                            timestampMillis = timestampMillis,
                        ),
                    )
                }
                if (settings.noPersonAlert && visionSafety.noPerson) {
                    val timestampMillis = visionSafety.timestampMillis
                    add(
                        BoardAlert(
                            id = "no-person-${timestampMillis / 10_000L}",
                            type = BoardAlertType.NoPerson,
                            timestampMillis = timestampMillis,
                        ),
                    )
                }
                if (settings.faceAlert && visionSafety.faceCoverRollover) {
                    val timestampMillis = visionSafety.timestampMillis
                    add(
                        BoardAlert(
                            id = "face-${timestampMillis / 10_000L}",
                            type = BoardAlertType.FaceCovered,
                            timestampMillis = timestampMillis,
                        ),
                    )
                }
                val boundaryTimestampMillis = latestBoundaryTimestampMillis(
                    latest = vision?.optJSONObject("latest"),
                    safetyZone = settings.safetyZone,
                    activityZoneMode = settings.activityZoneMode,
                )
                if (settings.boundaryAlert && boundaryTimestampMillis != null) {
                    add(
                        BoardAlert(
                            id = "boundary-${boundaryTimestampMillis / 10_000L}",
                            type = BoardAlertType.Boundary,
                            timestampMillis = boundaryTimestampMillis,
                        ),
                    )
                }
            }.sortedByDescending { it.timestampMillis }
        }.getOrDefault(emptyList())
    }

    override fun activityZone(): BoardActivityZone? {
        return runCatching {
            val response = client.get("/api/activity-zone")
            if (!response.ok) return@runCatching null
            val json = JSONObject(response.body)
            if (!json.optBoolean("ok", false)) return@runCatching null
            json.optJSONObject("result")?.toActivityZone()
        }.getOrNull()
    }

    override fun setActivityZone(zone: SafetyZone, mode: ActivityZoneMode): Boolean {
        val normalized = zone.normalized()
        val body = JSONObject()
            .put("mode", mode.apiValue)
            .put(
                "zone",
                JSONObject()
                    .put("left", normalized.left)
                    .put("top", normalized.top)
                    .put("right", normalized.right)
                    .put("bottom", normalized.bottom),
            )
        return runCatching {
            val response = client.post(
                "/api/activity-zone",
                body.toString().toByteArray(Charsets.UTF_8),
                "application/json; charset=utf-8",
            )
            response.ok && JSONObject(response.body).optBoolean("ok", false)
        }.getOrDefault(false)
    }

    override fun clearActivityZone(): Boolean {
        return runCatching {
            val response = client.delete("/api/activity-zone")
            response.ok && JSONObject(response.body).optBoolean("ok", false)
        }.getOrDefault(false)
    }

    override fun photos(): List<BoardPhoto> {
        return runCatching {
            val response = client.get("/api/photos")
            if (!response.ok) return@runCatching emptyList()
            val items = JSONArray(response.body)
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    add(
                        BoardPhoto(
                            id = item.optString("id", "board-photo-$index"),
                            capturedAtMillis = item.optLong("capturedAtMillis", System.currentTimeMillis()),
                            path = item.optString("path"),
                        ),
                    )
                }
            }.filter { it.path.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    override fun photoStream(photo: BoardPhoto): InputStream? {
        if (photo.path.isBlank()) return null
        return runCatching { client.getStream(photo.downloadPath()) }.getOrNull()
    }

    private fun BoardPhoto.downloadPath(): String {
        return when {
            path.startsWith("/api/photos/") -> path
            path.startsWith("/") -> path
            else -> "/api/photos/$path"
        }
    }

    private fun visionSafetyFromLatest(latest: JSONObject?): VisionSafetyState {
        val timestampMillis = latest?.timestampMillisOrNow() ?: System.currentTimeMillis()
        val person = latest?.optJSONArray("persons")?.optJSONObject(0)
        val keypoints = person?.optJSONArray("keypoints") ?: return VisionSafetyState(
            noPerson = true,
            faceCoverRollover = false,
            timestampMillis = timestampMillis,
        )

        val frontVisible = keypoints.facePointPresent(0) ||
            keypoints.facePointPresent(1) ||
            keypoints.facePointPresent(2)

        return VisionSafetyState(
            noPerson = false,
            faceCoverRollover = !frontVisible,
            timestampMillis = timestampMillis,
        )
    }

    private fun JSONObject.toVisionSafetyState(): VisionSafetyState {
        val timestampMillis = timestampMillisOrNow()
        return VisionSafetyState(
            noPerson = optBoolean("no_person", false),
            faceCoverRollover = optBoolean("face_cover_rollover", false),
            timestampMillis = timestampMillis,
        )
    }

    private fun latestBoundaryTimestampMillis(
        latest: JSONObject?,
        safetyZone: SafetyZone?,
        activityZoneMode: ActivityZoneMode,
    ): Long? {
        if (safetyZone == null) return null
        val timestampMillis = latest?.timestampMillisOrNow() ?: return null
        val person = latest.optJSONArray("persons")?.optJSONObject(0) ?: return null
        val box = person.optJSONArray("box") ?: return null
        if (box.length() < 4) return null
        val frameSize = latest.optJSONArray("frame_size")
        val frameWidth = frameSize?.optDouble(0, 320.0)?.takeIf { it > 0.0 } ?: 320.0
        val frameHeight = frameSize?.optDouble(1, 180.0)?.takeIf { it > 0.0 } ?: 180.0
        val personLeft = (box.optDouble(0) / frameWidth).toFloat()
        val personTop = (box.optDouble(1) / frameHeight).toFloat()
        val personRight = (box.optDouble(2) / frameWidth).toFloat()
        val personBottom = (box.optDouble(3) / frameHeight).toFloat()
        val zone = safetyZone.normalized()
        val fullyInside = personLeft >= zone.left &&
            personTop >= zone.top &&
            personRight <= zone.right &&
            personBottom <= zone.bottom
        val overlapsZone = personLeft < zone.right &&
            personRight > zone.left &&
            personTop < zone.bottom &&
            personBottom > zone.top
        val alert = when (activityZoneMode) {
            ActivityZoneMode.Safe -> !fullyInside
            ActivityZoneMode.Danger -> overlapsZone
        }
        return if (alert) timestampMillis else null
    }

    private fun JSONArray.facePointPresent(index: Int): Boolean {
        val point = optJSONArray(index) ?: return false
        val confidence = point.optDouble(2, 0.0)
        return confidence >= FACE_POINT_THRESHOLD
    }

    private fun JSONObject.timestampMillisOrNow(): Long {
        val updatedAtSeconds = optDouble("updated_at", 0.0)
        if (updatedAtSeconds > 0.0) return (updatedAtSeconds * 1_000).toLong()
        val capturedAtSeconds = optDouble("captured_at", 0.0)
        if (capturedAtSeconds > 0.0) return (capturedAtSeconds * 1_000).toLong()
        return System.currentTimeMillis()
    }

    private fun JSONObject.toLullabyStatus(): BoardLullabyStatus {
        val playback = optJSONObject("playback")
        val lastResult = playback?.optJSONObject("last_result")
        val tracksJson = optJSONArray("tracks") ?: JSONArray()
        val tracks = buildList {
            for (index in 0 until tracksJson.length()) {
                tracksJson.optJSONObject(index)?.toLullabyTrack()?.let(::add)
            }
        }
        val currentTrack = optJSONObject("current_track")?.toLullabyTrack()
        val volume = lastResult?.takeIf { it.has("volume") }?.optDouble("volume")?.toFloat()
        return BoardLullabyStatus(
            tracks = tracks,
            currentTrack = currentTrack,
            playing = optBoolean("playing", false),
            volume = volume,
        )
    }

    private fun JSONObject.toLullabyTrack(): BoardLullabyTrack {
        val durationSeconds = optDouble("duration_s", -1.0)
        val updatedSeconds = optDouble("updated_at", 0.0)
        return BoardLullabyTrack(
            id = optString("id"),
            title = optString("title", "板端摇篮曲"),
            durationMillis = if (durationSeconds >= 0.0) (durationSeconds * 1_000).toLong() else null,
            sizeBytes = optLong("stored_bytes", optLong("bytes", 0L)),
            updatedAtMillis = if (updatedSeconds > 0.0) (updatedSeconds * 1_000).toLong() else 0L,
        )
    }

    private fun JSONObject.toActivityZone(): BoardActivityZone {
        val mode = when (optString("mode", "safe")) {
            "danger" -> ActivityZoneMode.Danger
            else -> ActivityZoneMode.Safe
        }
        val zoneJson = optJSONObject("zone")
        val zone = if (zoneJson != null) {
            SafetyZone(
                left = zoneJson.optDouble("left", 0.0).toFloat(),
                top = zoneJson.optDouble("top", 0.0).toFloat(),
                right = zoneJson.optDouble("right", 0.0).toFloat(),
                bottom = zoneJson.optDouble("bottom", 0.0).toFloat(),
            ).normalized()
        } else {
            null
        }
        return BoardActivityZone(zone = zone, mode = mode)
    }

    private companion object {
        const val TALK_CHUNK_TIMEOUT_MS = 1_000
        const val MUSIC_UPLOAD_TIMEOUT_MS = 60_000
        const val FACE_POINT_THRESHOLD = 0.25
    }
}

private data class VisionSafetyState(
    val noPerson: Boolean,
    val faceCoverRollover: Boolean,
    val timestampMillis: Long,
)
