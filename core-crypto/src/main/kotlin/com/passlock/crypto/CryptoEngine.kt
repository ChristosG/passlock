package com.passlock.crypto

/**
 * Cryptographic primitives behind a narrow, swappable interface.
 * Pure-JVM so it behaves identically in unit tests and on-device.
 */
interface CryptoEngine {
    /** Derive a 256-bit key from a passphrase using Argon2id. */
    fun deriveKey(passphrase: CharArray, salt: ByteArray, params: KdfParams): ByteArray

    /** AEAD-encrypt. Returns nonce || ciphertext-with-tag. */
    fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /** AEAD-decrypt. Throws on tampering or wrong key. */
    fun aeadDecrypt(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray

    /** Cryptographically-random bytes (e.g. a fresh DEK). */
    fun randomBytes(length: Int): ByteArray

    /** Overwrite key material in place. */
    fun zeroize(bytes: ByteArray)
}
