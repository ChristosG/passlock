package com.passlock.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class RecoveryKitTest {

    @Test
    fun `encode then decode round-trips`() {
        val rnd = SecureRandom()
        repeat(200) {
            val secret = ByteArray(RecoveryKit.SECRET_BYTES).also(rnd::nextBytes)
            val decoded = RecoveryKit.decode(RecoveryKit.encode(secret))
            assertArrayEquals(secret, decoded)
        }
    }

    @Test
    fun `encoded form is grouped and uses the Crockford alphabet`() {
        val kit = RecoveryKit.encode(ByteArray(16) { it.toByte() })
        // 26 payload + 2 checksum = 28 chars, grouped in fours -> 7 groups joined by dashes.
        assertEquals(7, kit.split("-").size)
        assertTrue(kit.replace("-", "").all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
    }

    @Test
    fun `decoding is lenient about case, spaces and O-0 I-1 confusion`() {
        val secret = ByteArray(16) { (it * 7 + 3).toByte() }
        val canonical = RecoveryKit.encode(secret)
        val mangled = canonical.lowercase().replace("-", " ").replace('0', 'o').replace('1', 'l')
        assertArrayEquals(secret, RecoveryKit.decode(mangled))
    }

    @Test
    fun `a single mistyped checksum character is rejected`() {
        val kit = RecoveryKit.encode(ByteArray(16) { 9 })
        val last = kit.last()
        val wrong = if (last == 'Z') 'Y' else 'Z'
        assertNull(RecoveryKit.decode(kit.dropLast(1) + wrong))
    }

    @Test
    fun `a mistyped payload character is rejected by the checksum`() {
        val secret = ByteArray(16) { 0 }
        val kit = RecoveryKit.encode(secret)
        // Flip the first payload char to a definitely-different value.
        val first = kit.first()
        val wrong = if (first == 'Z') 'Y' else 'Z'
        assertNull(RecoveryKit.decode(wrong + kit.drop(1)))
    }

    @Test
    fun `garbage and wrong-length input yield null`() {
        assertNull(RecoveryKit.decode(""))
        assertNull(RecoveryKit.decode("hello world"))
        assertNull(RecoveryKit.decode("ABCD-EFGH")) // too short
    }

    @Test
    fun `a valid kit decodes to non-null`() {
        assertNotNull(RecoveryKit.decode(RecoveryKit.encode(ByteArray(16) { 42 })))
    }
}
