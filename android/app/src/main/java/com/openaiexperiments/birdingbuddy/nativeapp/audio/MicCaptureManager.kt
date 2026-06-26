package com.openaiexperiments.birdingbuddy.nativeapp.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.openaiexperiments.birdingbuddy.nativeapp.util.TARGET_SAMPLE_RATE
import com.openaiexperiments.birdingbuddy.nativeapp.util.downsampleInt16
import com.openaiexperiments.birdingbuddy.nativeapp.util.pcm16DurationMs
import com.openaiexperiments.birdingbuddy.nativeapp.util.shortArrayToLittleEndianBytes
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class MicCaptureManager(
    private val targetSampleRate: Int = TARGET_SAMPLE_RATE
) {
    data class CaptureResult(
        val pcm16Data: ByteArray,
        val durationMs: Long,
        val sourceSampleRate: Int
    )

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var sourceSampleRate: Int = targetSampleRate
    private val captureActive = AtomicBoolean(false)
    private val outputLock = Any()
    private val outputStream = ByteArrayOutputStream()

    val isCapturing: Boolean
        get() = captureActive.get()

    fun startCapture(): Result<Unit> {
        if (captureActive.get()) {
            return Result.success(Unit)
        }

        val selectedSampleRate =
            selectSampleRate()
                ?: return Result.failure(
                    IllegalStateException("No supported microphone sample rate found.")
                )

        val minBufferSize =
            AudioRecord.getMinBufferSize(
                selectedSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (minBufferSize <= 0) {
            return Result.failure(
                IllegalStateException("Unable to determine minimum audio buffer size.")
            )
        }

        val bufferSizeInBytes = minBufferSize * 2

        val record =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                selectedSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes
            )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Result.failure(
                IllegalStateException("Failed to initialize microphone capture.")
            )
        }

        synchronized(outputLock) {
            outputStream.reset()
        }

        audioRecord = record
        sourceSampleRate = selectedSampleRate
        captureActive.set(true)

        runCatching { record.startRecording() }
            .onFailure {
                captureActive.set(false)
                record.release()
                audioRecord = null
                return Result.failure(it)
            }

        val readBuffer = ShortArray(bufferSizeInBytes / 2)

        captureThread =
            Thread(
                {
                    while (captureActive.get()) {
                        val count =
                            runCatching {
                                record.read(readBuffer, 0, readBuffer.size)
                            }.getOrDefault(0)

                        if (count <= 0) {
                            continue
                        }

                        val outputChunk =
                            downsampleInt16(
                                input = readBuffer,
                                inputLength = count,
                                inputSampleRate = sourceSampleRate,
                                outputSampleRate = targetSampleRate
                            )

                        if (outputChunk.isEmpty()) {
                            continue
                        }

                        val bytes = shortArrayToLittleEndianBytes(outputChunk)
                        synchronized(outputLock) {
                            outputStream.write(bytes)
                        }
                    }
                },
                "mic-capture-thread"
            ).apply {
                isDaemon = true
                start()
            }

        return Result.success(Unit)
    }

    fun stopCaptureAndGetResult(): CaptureResult {
        stopInternal()

        val bytes = synchronized(outputLock) { outputStream.toByteArray() }
        val durationMs = pcm16DurationMs(bytes, targetSampleRate)

        synchronized(outputLock) {
            outputStream.reset()
        }

        return CaptureResult(
            pcm16Data = bytes,
            durationMs = durationMs,
            sourceSampleRate = sourceSampleRate
        )
    }

    fun cancelCapture() {
        stopInternal()
        synchronized(outputLock) {
            outputStream.reset()
        }
    }

    private fun stopInternal() {
        if (!captureActive.getAndSet(false)) {
            return
        }

        val record = audioRecord
        audioRecord = null

        runCatching { record?.stop() }
        runCatching { captureThread?.join(500) }
        captureThread = null
        runCatching { record?.release() }
    }

    private fun selectSampleRate(): Int? {
        val candidates = listOf(targetSampleRate, 48_000, 44_100, 32_000, 16_000)

        for (sampleRate in candidates) {
            val minBuffer =
                AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
            if (minBuffer > 0) {
                return sampleRate
            }
        }

        return null
    }
}
