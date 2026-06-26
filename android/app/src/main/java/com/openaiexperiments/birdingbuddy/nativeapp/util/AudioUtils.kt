package com.openaiexperiments.birdingbuddy.nativeapp.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val TARGET_SAMPLE_RATE = 24_000
const val PCM16_BYTES_PER_SAMPLE = 2
const val MIN_TURN_MS = 100L
const val MIN_TURN_AUDIO_BYTES =
    (TARGET_SAMPLE_RATE * MIN_TURN_MS * PCM16_BYTES_PER_SAMPLE / 1000).toInt()

fun pcm16DurationMs(pcm16Data: ByteArray, sampleRate: Int = TARGET_SAMPLE_RATE): Long {
    if (pcm16Data.isEmpty() || sampleRate <= 0) {
        return 0L
    }

    val sampleCount = pcm16Data.size / PCM16_BYTES_PER_SAMPLE
    return (sampleCount * 1000L) / sampleRate
}

fun shortArrayToLittleEndianBytes(samples: ShortArray, length: Int = samples.size): ByteArray {
    val clampedLength = min(length, samples.size)
    val out = ByteArray(clampedLength * 2)
    var outIndex = 0
    for (index in 0 until clampedLength) {
        val value = samples[index].toInt()
        out[outIndex] = (value and 0xFF).toByte()
        out[outIndex + 1] = ((value shr 8) and 0xFF).toByte()
        outIndex += 2
    }
    return out
}

fun downsampleInt16(
    input: ShortArray,
    inputLength: Int,
    inputSampleRate: Int,
    outputSampleRate: Int
): ShortArray {
    val length = min(inputLength, input.size)
    if (length <= 0) {
        return ShortArray(0)
    }

    if (inputSampleRate == outputSampleRate) {
        return input.copyOf(length)
    }

    val sampleRateRatio = inputSampleRate.toDouble() / outputSampleRate.toDouble()
    val outputLength = (length / sampleRateRatio).roundToInt().coerceAtLeast(1)
    val result = ShortArray(outputLength)

    var offsetResult = 0
    var offsetBuffer = 0

    while (offsetResult < result.size) {
        val nextOffsetBuffer = ((offsetResult + 1) * sampleRateRatio).roundToInt()
        var total = 0.0
        var count = 0

        var index = offsetBuffer
        while (index < min(nextOffsetBuffer, length)) {
            total += input[index] / 32768.0
            count += 1
            index += 1
        }

        val average = if (count > 0) total / count else 0.0
        val clamped = max(-1.0, min(1.0, average))
        result[offsetResult] =
            if (clamped < 0) {
                (clamped * 0x8000).toInt().toShort()
            } else {
                (clamped * 0x7FFF).toInt().toShort()
            }

        offsetResult += 1
        offsetBuffer = nextOffsetBuffer
    }

    return result
}

fun pcm16ToWav(pcm16Data: ByteArray, sampleRate: Int = TARGET_SAMPLE_RATE): ByteArray {
    val dataSize = pcm16Data.size
    val totalSize = 44 + dataSize

    val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt(36 + dataSize)
    buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

    buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
    buffer.putInt(16)
    buffer.putShort(1)
    buffer.putShort(1)
    buffer.putInt(sampleRate)
    buffer.putInt(sampleRate * 2)
    buffer.putShort(2)
    buffer.putShort(16)

    buffer.put("data".toByteArray(Charsets.US_ASCII))
    buffer.putInt(dataSize)
    buffer.put(pcm16Data)

    return buffer.array()
}

fun concatByteArrays(chunks: List<ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    for (chunk in chunks) {
        output.write(chunk)
    }
    return output.toByteArray()
}
