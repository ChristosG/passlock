package com.passlock.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom

class BouncyCastleCryptoEngine(
    private val secureRandom: SecureRandom = SecureRandom(),
) : CryptoEngine {

    override fun deriveKey(passphrase: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val argonParams = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKib)
            .withParallelism(params.parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator().apply { init(argonParams) }
        val key = ByteArray(KEY_LEN)
        generator.generateBytes(passphrase, key)
        return key
    }

    override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        TODO("implemented in Task 3")

    override fun aeadDecrypt(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray =
        TODO("implemented in Task 3")

    override fun randomBytes(length: Int): ByteArray =
        ByteArray(length).also(secureRandom::nextBytes)

    override fun zeroize(bytes: ByteArray) = bytes.fill(0)

    companion object {
        const val KEY_LEN = 32       // 256-bit
        const val NONCE_LEN = 12     // 96-bit GCM nonce
        const val TAG_BITS = 128
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
