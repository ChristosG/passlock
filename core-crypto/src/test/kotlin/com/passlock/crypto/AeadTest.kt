package com.passlock.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AeadTest {
    private val engine = BouncyCastleCryptoEngine()
    private val key = ByteArray(32) { 7 }
    private val aad = "v1".toByteArray()

    @Test
    fun `round-trips plaintext`() {
        val pt = "credit card pin 4821".toByteArray()
        val blob = engine.aeadEncrypt(key, pt, aad)
        assertArrayEquals(pt, engine.aeadDecrypt(key, blob, aad))
    }

    @Test
    fun `encrypting twice yields different ciphertext (random nonce)`() {
        val pt = "same".toByteArray()
        val a = engine.aeadEncrypt(key, pt, aad)
        val b = engine.aeadEncrypt(key, pt, aad)
        assert(!a.contentEquals(b))
    }

    @Test
    fun `flipping a ciphertext byte fails authentication`() {
        val blob = engine.aeadEncrypt(key, "secret".toByteArray(), aad)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) { engine.aeadDecrypt(key, blob, aad) }
    }

    @Test
    fun `wrong key fails authentication`() {
        val blob = engine.aeadEncrypt(key, "secret".toByteArray(), aad)
        val wrong = ByteArray(32) { 9 }
        assertThrows(Exception::class.java) { engine.aeadDecrypt(wrong, blob, aad) }
    }

    @Test
    fun `mismatched aad fails authentication`() {
        val blob = engine.aeadEncrypt(key, "secret".toByteArray(), aad)
        assertThrows(Exception::class.java) { engine.aeadDecrypt(key, blob, "v2".toByteArray()) }
    }
}
