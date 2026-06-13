package com.passlock.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PassphraseWrapTest {
    private val engine = BouncyCastleCryptoEngine()
    private val salt = ByteArray(16) { 3 }
    private val params = KdfParams.DAILY_DEFAULT
    private val dek = ByteArray(32) { (it * 5).toByte() }

    @Test
    fun `unwrap recovers the original DEK`() {
        val wrapped = PassphraseWrap.wrap(engine, "master-pw".toCharArray(), salt, params, dek)
        val recovered = PassphraseWrap.unwrap(engine, "master-pw".toCharArray(), salt, params, wrapped)
        assertArrayEquals(dek, recovered)
    }

    @Test
    fun `wrong passphrase cannot unwrap`() {
        val wrapped = PassphraseWrap.wrap(engine, "master-pw".toCharArray(), salt, params, dek)
        assertThrows(Exception::class.java) {
            PassphraseWrap.unwrap(engine, "WRONG".toCharArray(), salt, params, wrapped)
        }
    }
}
