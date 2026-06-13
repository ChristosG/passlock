package com.passlock.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages non-extractable keys in the Android Keystore.
 *
 * The hardware key ([HW_ALIAS]) provides the *outer* wrap of the vault's
 * password-wrapped DEK: it lives in StrongBox (or the TEE) and can never be
 * read out of the secure element, so an attacker who images storage cannot
 * begin an offline brute-force of the master password. The key requires no
 * user authentication, so cold-start stays "password only" while remaining
 * hardware-protected.
 */
class KeystoreManager {
    private val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Informational: "StrongBox", "TEE", or "unknown". */
    var hardwareBacking: String = "unknown"
        private set

    private fun hardwareKey(): SecretKey {
        (keystore.getKey(HW_ALIAS, null) as? SecretKey)?.let {
            if (hardwareBacking == "unknown") hardwareBacking = "secure element"
            return it
        }
        return generateHardwareKey()
    }

    private fun generateHardwareKey(): SecretKey {
        // Prefer StrongBox (dedicated secure chip); fall back to the TEE.
        return try {
            generate(baseSpec().setIsStrongBoxBacked(true).build()).also { hardwareBacking = "StrongBox" }
        } catch (e: Exception) {
            generate(baseSpec().build()).also { hardwareBacking = "TEE" }
        }
    }

    private fun baseSpec() = KeyGenParameterSpec.Builder(
        HW_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)

    private fun generate(spec: KeyGenParameterSpec): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(spec)
        return kg.generateKey()
    }

    /** Encrypt with the hardware key. Output = iv(12) || ciphertext+tag. */
    fun hwEncrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, hardwareKey())
        val iv = cipher.iv
        return iv + cipher.doFinal(data)
    }

    fun hwDecrypt(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, IV_LEN)
        val ct = blob.copyOfRange(IV_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, hardwareKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val HW_ALIAS = "passlock.hw.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
