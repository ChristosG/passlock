package com.passlock.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    override fun aeadDecrypt(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size >= NONCE_LEN + TAG_BITS / 8) { "blob too short" }
        val nonce = blob.copyOfRange(0, NONCE_LEN)
        val ciphertext = blob.copyOfRange(NONCE_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)   // throws AEADBadTagException on tamper/wrong key/aad
    }

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
