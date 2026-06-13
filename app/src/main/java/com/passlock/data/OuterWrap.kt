package com.passlock.data

/**
 * The outer (hardware) wrap around the password-wrapped DEK.
 *
 * In production this is backed by a non-extractable Android Keystore key
 * ([KeystoreOuterWrap]); in pure-JVM unit tests it is the identity, so the
 * password + AEAD logic can be verified without a secure element.
 */
interface OuterWrap {
    fun wrap(blob: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
}

/** No-op wrap for tests and as a safe default. */
object IdentityOuterWrap : OuterWrap {
    override fun wrap(blob: ByteArray): ByteArray = blob
    override fun unwrap(blob: ByteArray): ByteArray = blob
}

/** Hardware-backed wrap using the StrongBox/TEE Keystore key. */
class KeystoreOuterWrap(private val keystore: KeystoreManager) : OuterWrap {
    override fun wrap(blob: ByteArray): ByteArray = keystore.hwEncrypt(blob)
    override fun unwrap(blob: ByteArray): ByteArray = keystore.hwDecrypt(blob)
}
