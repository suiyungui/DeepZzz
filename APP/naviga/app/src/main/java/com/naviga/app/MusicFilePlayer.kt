package com.naviga.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

data class MusicTrack(
    val uri: Uri?,
    val title: String,
    val sizeBytes: Long,
    val durationMillis: Long?,
    val boardTrackId: String? = null,
)

data class MusicPlaybackResult(
    val ok: Boolean,
    val message: String,
    val track: MusicTrack? = null,
)

class MusicFilePlayer(
    private val context: Context,
    private val boardApiProvider: () -> BoardApi,
) {
    fun loadTrack(uri: Uri): MusicTrack {
        val resolver = context.contentResolver
        val nameAndSize = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                name to size
            } else {
                null to 0L
            }
        } ?: (null to 0L)
        val duration = runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull()
        return MusicTrack(
            uri = uri,
            title = nameAndSize.first ?: "未命名音频",
            sizeBytes = nameAndSize.second,
            durationMillis = duration,
        )
    }

    fun play(track: MusicTrack, volume: Float): MusicPlaybackResult {
        track.boardTrackId?.let { boardTrackId ->
            val started = boardApiProvider().playStoredLullaby(boardTrackId, volume.coerceIn(0f, 1f))
            return if (started) {
                MusicPlaybackResult(true, "板端摇篮曲播放中")
            } else {
                MusicPlaybackResult(false, "板端播放失败")
            }
        }
        val uri = track.uri ?: return MusicPlaybackResult(false, "摇篮曲文件不可用")
        val wav = runCatching { decodeToWav(uri) }.getOrElse {
            return MusicPlaybackResult(false, "音频解码失败")
        }
        val uploaded = boardApiProvider().uploadLullaby(wav, track.title, volume.coerceIn(0f, 1f))
        val boardTrack = uploaded?.track?.toMusicTrack()
        return if (boardTrack != null) {
            MusicPlaybackResult(true, "已存入板端并循环播放", boardTrack)
        } else {
            MusicPlaybackResult(false, "板端播放失败")
        }
    }

    fun stop(): MusicPlaybackResult {
        val stopped = boardApiProvider().stopMusicAudio()
        return if (stopped) {
            MusicPlaybackResult(true, "已停止板端播放")
        } else {
            MusicPlaybackResult(false, "停止播放失败")
        }
    }

    fun setVolume(volume: Float): MusicPlaybackResult {
        val updated = boardApiProvider().setMusicVolume(volume.coerceIn(0f, 1f))
        return if (updated) {
            MusicPlaybackResult(true, "音量已调整")
        } else {
            MusicPlaybackResult(false, "音量调整失败")
        }
    }

    private fun decodeToWav(uri: Uri): ByteArray {
        val extractor = MediaExtractor()
        val fd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("cannot open audio file")
        fd.use {
            extractor.setDataSource(it.fileDescriptor)
        }
        try {
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) error("no audio track")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("missing mime")
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                return decodeSelectedTrackToWav(extractor, codec, format)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return index
        }
        return -1
    }

    private fun decodeSelectedTrackToWav(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
    ): ByteArray {
        codec.configure(inputFormat, null, null, 0)
        codec.start()
        val bufferInfo = MediaCodec.BufferInfo()
        val pcm = ByteArrayOutputStream()
        var inputDone = false
        var outputDone = false
        var outputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var outputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var outputPcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    val sampleSize = if (inputBuffer != null) {
                        extractor.readSampleData(inputBuffer, 0)
                    } else {
                        -1
                    }
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outputChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    outputPcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> {
                    if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            writePcm16(outputBuffer, pcm, outputPcmEncoding)
                        }
                        outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

        return wavBytes(
            pcm = pcm.toByteArray(),
            sampleRate = outputSampleRate,
            channels = max(1, outputChannels),
        )
    }

    private fun writePcm16(buffer: ByteBuffer, out: ByteArrayOutputStream, encoding: Int) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                while (floatBuffer.hasRemaining()) {
                    val sample = floatBuffer.get().coerceIn(-1f, 1f)
                    out.writeShortLE((sample * Short.MAX_VALUE).toInt())
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                while (buffer.hasRemaining()) {
                    val unsigned = buffer.get().toInt() and 0xFF
                    out.writeShortLE((unsigned - 128) shl 8)
                }
            }
            AudioFormat.ENCODING_PCM_16BIT -> {
                val copy = ByteArray(buffer.remaining())
                buffer.get(copy)
                out.write(copy)
            }
            else -> error("unsupported PCM encoding: $encoding")
        }
    }

    private fun wavBytes(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val byteRate = sampleRate * channels * BYTES_PER_SAMPLE
        val blockAlign = channels * BYTES_PER_SAMPLE
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.writeIntLE(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.writeIntLE(16)
        out.writeShortLE(1)
        out.writeShortLE(channels)
        out.writeIntLE(sampleRate)
        out.writeIntLE(byteRate)
        out.writeShortLE(blockAlign)
        out.writeShortLE(16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.writeIntLE(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeIntLE(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun ByteArrayOutputStream.writeShortLE(value: Int) {
        write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
        const val BYTES_PER_SAMPLE = 2
    }
}

fun BoardLullabyTrack.toMusicTrack(): MusicTrack {
    return MusicTrack(
        uri = null,
        title = title,
        sizeBytes = sizeBytes,
        durationMillis = durationMillis,
        boardTrackId = id,
    )
}
