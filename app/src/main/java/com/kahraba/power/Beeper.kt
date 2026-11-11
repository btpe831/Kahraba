package com.kahraba.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays the alerts on the alarm channel, so they sound even when the phone is on silent.
 *
 * Kahraba back (1.5.09): 4 fast beeps, pause, 4, pause, 4 — seven times, about 18 seconds.
 * Moteur took over (1.5.09): three 3-second beeps with 3 seconds between them, about 15 seconds.
 * Phone moved: 2 beeps, pause, 2 beeps, pause, 2 beeps.
 * Power cuts only: one long beep when the power goes off, 4 quick beeps twice when it's back.
 * All at a kitchen-timer pitch ([PITCH_HZ]); the rhythm tells them apart.
 * While an alert beeps, the power button or a volume button stops it (1.5.09), from any app.
 */
class Beeper(private val ctx: Context) {
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val pending = mutableListOf<ScheduledFuture<*>>()
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val kahrabaSound: ShortArray by lazy {
        val parts = ArrayList<ShortArray>()
        repeat(KAHRABA_ROUNDS) { round ->
            repeat(3) { group ->
                repeat(4) {
                    parts += tone(70, PITCH_HZ)
                    parts += silence(55)
                }
                if (group < 2) parts += silence(225)
            }
            if (round < KAHRABA_ROUNDS - 1) parts += silence(750)
        }
        concat(parts)
    }

    /** One 3-second beep: power off (power cuts only). */
    private val moteurSound: ShortArray by lazy { tone(3000, PITCH_HZ) }
    /** Moteur took over: three 3-second beeps, 3 seconds apart. */
    private val moteurAlertSound: ShortArray by lazy {
        concat(listOf(moteurSound, silence(3000), moteurSound, silence(3000), moteurSound))
    }

    private val phoneMovedSound: ShortArray by lazy {
        concat((1..3).flatMap { listOf(tone(140, PITCH_HZ), silence(110), tone(140, PITCH_HZ), silence(600)) })
    }

    private val quickKahrabaSound: ShortArray by lazy {
        concat((1..4).flatMap { listOf(tone(140, PITCH_HZ), silence(110)) })
    }
    private val powerBackSound: ShortArray by lazy { concat(listOf(quickKahrabaSound, silence(450), quickKahrabaSound)) }
    private val quickMoteurSound: ShortArray by lazy { tone(1500, PITCH_HZ) }
    private val quickNoFlickerSound: ShortArray by lazy {
        concat((1..2).flatMap { listOf(tone(140, PITCH_HZ), silence(160)) })
    }

    /** Quick check "no flicker": 2 short beeps. */
    @Synchronized
    fun quickNoFlicker() {
        cancel()
        pending += exec.schedule(Runnable { play(quickNoFlickerSound, respectQuiet = false) }, 0, TimeUnit.MILLISECONDS)
    }

    /** Quick check result: 4 short beeps for kahraba, one long beep for moteur. Plays right away. */
    @Synchronized
    fun quickResult(kahraba: Boolean) {
        cancel()
        val pcm = if (kahraba) quickKahrabaSound else quickMoteurSound
        pending += exec.schedule(Runnable { play(pcm, respectQuiet = false) }, 0, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun kahraba(respectQuiet: Boolean = true) = alert(kahrabaSound, respectQuiet)

    @Synchronized
    fun moteur(respectQuiet: Boolean = true) = alert(moteurAlertSound, respectQuiet)

    /** The phone moved and its charger came off: check it's back on its stand. */
    @Synchronized
    fun phoneMoved(respectQuiet: Boolean = true) = alert(phoneMovedSound, respectQuiet)

    /** Power cuts only: the power went off. */
    @Synchronized
    fun powerOff(respectQuiet: Boolean = true) = alert(moteurSound, respectQuiet)

    /** Power cuts only: the power is back. */
    @Synchronized
    fun powerBack(respectQuiet: Boolean = true) = alert(powerBackSound, respectQuiet)

    /** An alert: plays now, and the phone's buttons can stop it. */
    private fun alert(pcm: ShortArray, respectQuiet: Boolean) {
        cancel()
        if (respectQuiet && Prefs.isQuietNow(ctx)) return
        val gen = ++alertGen
        main.post { startButtons(gen) }
        pending += exec.schedule(Runnable {
            try {
                play(pcm, respectQuiet = false)
            } finally {
                main.post { stopButtons(gen) }
            }
        }, 0, TimeUnit.MILLISECONDS)
    }

    /** Stops any alert that is playing or waiting to play. */
    @Synchronized
    fun cancel() {
        pending.forEach { it.cancel(true) }
        pending.clear()
        val gen = alertGen
        main.post { stopButtons(gen) }
    }

    fun shutdown() {
        cancel()
        exec.shutdownNow()
    }

    // ---- The phone's buttons stop the beeps (1.5.09) ----

    private val main = Handler(Looper.getMainLooper())
    /** Counts alerts, so a finished one never stops the buttons for the next. */
    @Volatile private var alertGen = 0
    private var buttonsGen = -1
    private var buttonsFromMs = 0L
    private var session: MediaSession? = null
    private var screenReceiver: BroadcastReceiver? = null

    /** A button was pressed while it beeped. */
    private fun stopByButton() {
        synchronized(this) {
            pending.forEach { it.cancel(true) }
            pending.clear()
        }
        stopButtons(buttonsGen)
    }

    /**
     * Power button: Android says when the screen turns on or off (ignored for the first moment, in
     * case the alert itself wakes the screen). Volume buttons: a media session that is "playing"
     * gets them while the beeps sound, from any app (unless another app is playing sound).
     */
    private fun startButtons(gen: Int) {
        if (gen != alertGen) return
        stopButtons(buttonsGen)
        buttonsGen = gen
        buttonsFromMs = SystemClock.elapsedRealtime()
        runCatching {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (SystemClock.elapsedRealtime() - buttonsFromMs >= SCREEN_GRACE_MS) stopByButton()
                }
            }
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ContextCompat.registerReceiver(ctx.applicationContext, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver = r
        }
        runCatching {
            val s = MediaSession(ctx.applicationContext, "kahraba-beeps")
            s.setPlaybackToRemote(object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 1, 0) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction != 0) main.post { stopByButton() }
                }
            })
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                    .build(),
            )
            s.isActive = true
            session = s
        }
    }

    private fun stopButtons(gen: Int) {
        if (gen != buttonsGen) return
        buttonsGen = -1
        screenReceiver?.let { r -> runCatching { ctx.applicationContext.unregisterReceiver(r) } }
        screenReceiver = null
        session?.let { s ->
            runCatching {
                s.isActive = false
                s.release()
            }
        }
        session = null
    }

    private fun play(pcm: ShortArray, respectQuiet: Boolean) {
        if (respectQuiet && Prefs.isQuietNow(ctx)) return
        val stream = AudioManager.STREAM_ALARM
        val oldVolume = audio.getStreamVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        val target = Math.round(max * Prefs.beepVolume(ctx)).toInt().coerceIn(1, max)
        var track: AudioTrack? = null
        try {
            runCatching { audio.setStreamVolume(stream, target, 0) }
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
            runCatching { audio.setStreamVolume(stream, oldVolume, 0) }
        }
    }

    companion object {
        private const val RATE = 22050
        /** A typical kitchen-timer beep; phone speakers are loud at this pitch. */
        private const val PITCH_HZ = 3000.0
        /** Kahraba's pattern (4 fast beeps × 3) this many times: about 18 seconds. */
        private const val KAHRABA_ROUNDS = 7
        /** Screen changes this soon after an alert starts don't count as a button press. */
        private const val SCREEN_GRACE_MS = 1_500L

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
