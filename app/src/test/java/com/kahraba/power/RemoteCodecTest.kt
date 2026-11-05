package com.kahraba.power

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCodecTest {
    private val msg = RemoteCodec.Message(
        RemoteCodec.Kind.ALERT, Source.KAHRABA, 1_793_841_665_000, 1_793_841_965_000, "Pixel 2",
        room = "Guest bathroom", title = "Kahraba since 6:01 AM", text = "6:01 AM – Light got 23% brighter | and stayed steady", beep = RemoteCodec.BEEP_KAHRABA,
    )

    @Test
    fun theCodeCarriesTopicAndKey() {
        val p = RemoteCodec.newPairing()
        assertTrue(p.topic, p.topic.matches(Regex("kon[a-z0-9]{24}")))
        val back = RemoteCodec.parse("Get my power alerts: ${p.code} (open the app first)")
        assertNotNull(back)
        assertEquals(p.topic, back!!.topic)
        assertArrayEquals(p.key, back.key)
        assertNull(RemoteCodec.parse("kahraba-alerts:konabc:xyz"))
        assertNull(RemoteCodec.parse("hello"))
    }

    @Test
    fun messagesSurviveTheTrip() {
        assertEquals(msg, RemoteCodec.decode(RemoteCodec.encode(msg)))
        val alive = RemoteCodec.Message(RemoteCodec.Kind.ALIVE, Source.MOTEUR, 1L, 2L, "Pixel 2")
        assertEquals(alive, RemoteCodec.decode(RemoteCodec.encode(alive)))
        assertNull(RemoteCodec.decode("2|ALERT|KAHRABA"))
        assertNull(RemoteCodec.decode("rubbish"))
    }

    @Test
    fun onlyTheRightKeyOpensIt() {
        val p = RemoteCodec.newPairing()
        val sealed = RemoteCodec.seal(p.key, RemoteCodec.encode(msg))
        assertTrue("no plain text on the relay", !sealed.contains("Kahraba") && !sealed.contains("Pixel"))
        assertEquals(msg, RemoteCodec.decode(RemoteCodec.open(p.key, sealed)!!))
        assertNull(RemoteCodec.open(RemoteCodec.newPairing().key, sealed))
        assertNull(RemoteCodec.open(p.key, sealed.dropLast(4) + "AAAA"))
        // A fresh nonce each time: the same alert never looks the same twice.
        assertNotEquals(sealed, RemoteCodec.seal(p.key, RemoteCodec.encode(msg)))
        // Small enough for ntfy's 4,096-byte messages.
        assertTrue(sealed.length < 2000)
    }

    @Test
    fun agoReadsNaturally() {
        assertEquals("just now", RemoteCodec.ago(20_000))
        assertEquals("3 min ago", RemoteCodec.ago(3 * 60_000L + 5_000))
        assertEquals("2 h 5 min ago", RemoteCodec.ago(125 * 60_000L))
    }
}
