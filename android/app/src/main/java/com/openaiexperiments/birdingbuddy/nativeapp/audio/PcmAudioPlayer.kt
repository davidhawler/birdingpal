package com.openaiexperiments.birdingbuddy.nativeapp.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.openaiexperiments.birdingbuddy.nativeapp.util.TARGET_SAMPLE_RATE
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class PcmAudioPlayer(
    private val sampleRate: Int = TARGET_SAMPLE_RATE
) {
    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        val minBuffer =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        val bufferSize = if (minBuffer > 0) minBuffer * 2 else sampleRate

        val track =
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

        audioTrack = track
        running.set(true)
        track.play()

        playbackThread =
            Thread(
                {
                    while (running.get()) {
                        val chunk = queue.take()
                        if (chunk.isEmpty()) {
                            continue
                        }

                        val localTrack = audioTrack ?: continue
                        if (localTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            localTrack.play()
                        }

                        var offset = 0
                        while (offset < chunk.size && running.get()) {
                            val written =
                                localTrack.write(
                                    chunk,
                                    offset,
                                    chunk.size - offset,
                                    AudioTrack.WRITE_BLOCKING
                                )
                            if (written <= 0) {
                                break
                            }
                            offset += written
                        }
                    }
                },
                "pcm-audio-player-thread"
            ).apply {
                isDaemon = true
                start()
            }
    }

    fun enqueuePcm16(chunk: ByteArray) {
        if (chunk.isEmpty()) {
            return
        }

        if (!started.get()) {
            start()
        }

        queue.offer(chunk)
    }

    fun stopAndFlush() {
        queue.clear()
        val track = audioTrack ?: return

        runCatching {
            track.pause()
            track.flush()
            track.play()
        }
    }

    fun release() {
        if (!started.get()) {
            return
        }

        running.set(false)
        queue.offer(ByteArray(0))

        runCatching { playbackThread?.join(400) }
        playbackThread = null

        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        }

        audioTrack = null
        started.set(false)
    }
}
