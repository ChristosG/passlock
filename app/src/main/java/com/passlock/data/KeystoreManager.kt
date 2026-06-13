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
 * - [hwEncrypt]/[hwDecrypt] use a StrongBox/TEE key (no user auth) as the *outer*
 *   wrap of the password-wrapped DEK, so a storage image can't be brute-forced offline.
 * - The biometric key requires a fresh strong-biometric auth for every use (via a
 *   [Cipher] passed to a BiometricPrompt CryptoObject). It is invalidated if the user
 *   enrolls a new biometric.
 */
class KeystoreManager {
    private val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Informational: "StrongBox", "TEE", or "secure element". */
    var hardwareBacking: String = "unknown"
        private set

    // ---------------- Hardware outer-wrap key (no user auth) ----------------

    private fun hardwareKey(): SecretKey {
        (keystore.getKey(HW_ALIAS, null) as? SecretKey)?.let {
            if (hardwareBacking == "unknown") hardwareBacking = "secure element"
            return it
        }
        return try {
            generate(hwSpec().setIsStrongBoxBacked(true).build()).also { hardwareBacking = "StrongBox" }
        } catch (e: Exception) {
            generate(hwSpec().build()).also { hardwareBacking = "TEE" }
        }
    }

    private fun hwSpec() = KeyGenParameterSpec.Builder(
        HW_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)

    fun hwEncrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, hardwareKey())
        return cipher.iv + cipher.doFinal(data)
    }

    fun hwDecrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, hardwareKey(), GCMParameterSpec(128, blob.copyOfRange(0, IV_LEN)))
        return cipher.doFinal(blob.copyOfRange(IV_LEN, blob.size))
    }

    // ---------------- Biometric-gated key (auth required per use) ----------------

    private fun biometricKey(): SecretKey {
        (keystore.getKey(BIO_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            BIO_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .build()
        return generate(spec)
    }

    fun hasBiometricKey(): Boolean = keystore.containsAlias(BIO_ALIAS)

    fun deleteBiometricKey() {
        if (keystore.containsAlias(BIO_ALIAS)) keystore.deleteEntry(BIO_ALIAS)
    }

    /** A cipher to hand to BiometricPrompt; only usable for doFinal() after auth succeeds. */
    fun biometricEncryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, biometricKey()) }

    fun biometricDecryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, biometricKey(), GCMParameterSpec(128, iv)) }

    private fun generate(spec: KeyGenParameterSpec): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(spec)
        return kg.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val HW_ALIAS = "passlock.hw.v1"
        const val BIO_ALIAS = "passlock.bio.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
