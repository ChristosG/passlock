package com.passlock.crypto

/**
 * Wraps/unwraps a Data Encryption Key under a key derived from a passphrase.
 * The KDF version tag is bound as AAD so a future algorithm change is authenticated.
 */
object PassphraseWrap {
    private val AAD = "passlock.wrap.v1".toByteArray()

    fun wrap(
        engine: CryptoEngine,
        passphrase: CharArray,
        salt: ByteArray,
        params: KdfParams,
        dek: ByteArray,
    ): ByteArray {
        val kek = engine.deriveKey(passphrase, salt, params)
        try {
            return engine.aeadEncrypt(kek, dek, AAD)
        } finally {
            engine.zeroize(kek)
        }
    }

    fun unwrap(
        engine: CryptoEngine,
        passphrase: CharArray,
        salt: ByteArray,
        params: KdfParams,
        wrapped: ByteArray,
    ): ByteArray {
        val kek = engine.deriveKey(passphrase, salt, params)
        try {
            return engine.aeadDecrypt(kek, wrapped, AAD)
        } finally {
            engine.zeroize(kek)
        }
    }
}
