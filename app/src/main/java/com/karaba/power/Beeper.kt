package com.karaba.power

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays the alerts on the alarm channel, so they sound even when the phone is on silent.
 *
 * Kahraba back: 4 quick beeps, pause, 4 beeps, pause, 4 beeps — played twice.
 * Moteur took over: one 3-second beep, then again after 1 and 2 minutes.
 */
class Beeper(private val ctx: Context) {
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val pending = mutableListOf<ScheduledFuture<*>>()
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val kahrabaSound: ShortArray by lazy {
        val parts = ArrayList<ShortArray>()
        repeat(2) { round ->
            repeat(3) { group ->
                repeat(4) {
                    parts += tone(140, 2200.0)
                    parts += silence(110)
                }
                if (group < 2) parts += silence(450)
            }
            if (round == 0) parts += silence(1500)
        }
        concat(parts)
    }

    private val moteurSound: ShortArray by lazy { tone(3000, 950.0) }

    @Synchronized
    fun kahraba(respectQuiet: Boolean = true) {
        cancel()
        pending += exec.schedule(Runnable { play(kahrabaSound, respectQuiet) }, 0, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun moteur(respectQuiet: Boolean = true) {
        cancel()
        for (i in 0 until 3) {
            pending += exec.schedule(Runnable { play(moteurSound, respectQuiet) }, i * 60L, TimeUnit.SECONDS)
        }
    }

    /** Stops any alert that is playing or waiting to play. */
    @Synchronized
    fun cancel() {
        pending.forEach { it.cancel(true) }
        pending.clear()
    }

    fun shutdown() {
        cancel()
        exec.shutdownNow()
    }

    private fun play(pcm: ShortArray, respectQuiet: Boolean) {
        if (respectQuiet && Prefs.isQuietNow(ctx)) return
        val stream = AudioManager.STREAM_ALARM
        val oldVolume = audio.getStreamVolume(stream)
        val force = Prefs.forceVolume(ctx)
        var track: AudioTrack? = null
        try {
            if (force) runCatching { audio.setStreamVolume(stream, audio.getStreamMaxVolume(stream), 0) }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / RATE + 150)
        } catch (_: InterruptedException) {
            // Cancelled because the source changed again.
        } catch (_: Exception) {
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            if (force) runCatching { audio.setStreamVolume(stream, oldVolume, 0) }
        }
    }

    companion object {
        private const val RATE = 22050

        private fun tone(ms: Int, freq: Double): ShortArray {
            val n = RATE * ms / 1000
            val fade = RATE * 8 / 1000 // 8 ms fade in/out avoids clicks
            return ShortArray(n) { i ->
                val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                (sin(2 * PI * freq * i / RATE) * env * 0.9 * Short.MAX_VALUE).toInt().toShort()
            }
        }

        private fun silence(ms: Int) = ShortArray(RATE * ms / 1000)

        private fun concat(parts: List<ShortArray>): ShortArray {
            val out = ShortArray(parts.sumOf { it.size })
            var pos = 0
            for (p in parts) {
                p.copyInto(out, pos)
                pos += p.size
            }
            return out
        }
    }
}
