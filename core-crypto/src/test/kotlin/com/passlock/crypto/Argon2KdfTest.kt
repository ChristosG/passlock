package com.passlock.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class Argon2KdfTest {
    private val engine = BouncyCastleCryptoEngine()
    private val params = KdfParams.DAILY_DEFAULT
    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun `derives a 32-byte key`() {
        val key = engine.deriveKey("correct horse".toCharArray(), salt, params)
        assertEquals(32, key.size)
    }

    @Test
    fun `is deterministic for the same inputs`() {
        val a = engine.deriveKey("pw".toCharArray(), salt, params)
        val b = engine.deriveKey("pw".toCharArray(), salt, params)
        assertArrayEquals(a, b)
    }

    @Test
    fun `different salt yields a different key`() {
        val a = engine.deriveKey("pw".toCharArray(), salt, params)
        val b = engine.deriveKey("pw".toCharArray(), ByteArray(16) { (it + 1).toByte() }, params)
        assertFalse(a.contentEquals(b))
    }
}
