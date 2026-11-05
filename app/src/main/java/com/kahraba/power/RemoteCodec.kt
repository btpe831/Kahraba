package com.kahraba.power

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Alerts on other phones (1.5.0): the pairing code, the message format and its encryption.
 * Plain Kotlin, so it is tested on the computer.
 *
 * The phone running Always-on mode posts each message to a private ntfy.sh topic; paired phones
 * listen to it. The code (shown as a QR code, or shared as text) holds the topic and a 256-bit key,
 * and every message is encrypted with AES-GCM, so the relay only ever sees random-looking text.
 */
object RemoteCodec {
    const val SERVER = "https://ntfy.sh"
    private const val CODE_PREFIX = "kahraba-alerts:"
    private const val FORMAT = "1"
    private val TOPIC_CHARS = "abcdefghijkmnpqrstuvwxyz23456789"

    class Pairing(val topic: String, val key: ByteArray) {
        val code: String get() = CODE_PREFIX + topic + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(key)
    }

    fun newPairing(random: SecureRandom = SecureRandom()): Pairing {
        val topic = "kon" + (1..24).map { TOPIC_CHARS[random.nextInt(TOPIC_CHARS.length)] }.joinToString("")
        return Pairing(topic, ByteArray(32).also(random::nextBytes))
    }

    /** Finds a code in [text] (a scanned QR code, or a whole shared message); null if there isn't one. */
    fun parse(text: String): Pairing? {
        val m = Regex("""kahraba-alerts:(kon[a-z0-9]{24}):([A-Za-z0-9_-]{43})""").find(text) ?: return null
        val key = runCatching { Base64.getUrlDecoder().decode(m.groupValues[2]) }.getOrNull() ?: return null
        return if (key.size == 32) Pairing(m.groupValues[1], key) else null
    }

    enum class Kind { ALERT, ALIVE, STOPPED }

    const val BEEP_KAHRABA = "kahraba"
    const val BEEP_MOTEUR = "moteur"
    const val BEEP_OFF = "off"
    const val BEEP_BACK = "back"
    const val BEEP_MOVED = "moved"

    /**
     * One message. [source] and [sinceMs] are what the sending phone shows now; [title] and [text]
     * are the alert as that phone showed it (empty for ALIVE and STOPPED).
     */
    data class Message(
        val kind: Kind,
        val source: Source,
        val sinceMs: Long,
        val timeMs: Long,
        val device: String,
        val room: String = "",
        val title: String = "",
        val text: String = "",
        /** Which beep the sending phone played ([BEEP_KAHRABA] and so on), or empty for none. */
        val beep: String = "",
    )

    fun encode(m: Message): String = listOf(
        FORMAT, m.kind.name, m.source.name, m.sinceMs.toString(), m.timeMs.toString(),
        esc(m.device), esc(m.room), esc(m.title), esc(m.text), m.beep,
    ).joinToString("|")

    fun decode(s: String): Message? = runCatching {
        val p = s.split("|")
        if (p[0] != FORMAT || p.size < 10) return null
        Message(
            kind = Kind.valueOf(p[1]),
            source = Source.valueOf(p[2]),
            sinceMs = p[3].toLong(),
            timeMs = p[4].toLong(),
            device = unesc(p[5]),
            room = unesc(p[6]),
            title = unesc(p[7]),
            text = unesc(p[8]),
            beep = p[9],
        )
    }.getOrNull()

    /** Encrypts for the relay: base64 of a random 12-byte nonce followed by the AES-GCM ciphertext. */
    fun seal(key: ByteArray, plain: String, random: SecureRandom = SecureRandom()): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** Null if it wasn't sealed with [key] (another code, or changed on the way). */
    fun open(key: ByteArray, sealed: String): String? = runCatching {
        val all = Base64.getDecoder().decode(sealed.trim())
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }.getOrNull()

    /** How long ago, for "last heard 3 min ago". */
    fun ago(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ${min % 60} min ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }

    private fun esc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun unesc(s: String) = URLDecoder.decode(s, "UTF-8")
}
