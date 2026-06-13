package com.passlock.data

import com.passlock.crypto.BouncyCastleCryptoEngine
import com.passlock.crypto.KdfParams
import com.passlock.crypto.PassphraseWrap
import com.passlock.domain.Vault
import com.passlock.domain.VaultSerialization
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher

/** A successfully-opened vault: the in-memory DEK plus the decrypted contents. */
class Opened(val dek: ByteArray, val vault: Vault)

/**
 * Encrypted vault persistence. The main file holds:
 *   magic | format | salt | argon2 params | outer(inner(DEK)) | AEAD(vault) under the DEK
 *
 * where inner = AEAD under an Argon2id key from the master password and outer = the
 * hardware ([OuterWrap]) layer. A separate "bio.plk" file holds the DEK wrapped by a
 * biometric-gated Keystore key for quick re-unlock.
 */
class VaultStore(filesDir: File, private val outerWrap: OuterWrap = IdentityOuterWrap) {
    private val engine = BouncyCastleCryptoEngine()
    private val file = File(filesDir, "vault.plk")
    private val bioFile = File(filesDir, "bio.plk")
    private val rnd = SecureRandom()
    private val vaultAad = "passlock.vault.v1".toByteArray()

    fun exists(): Boolean = file.exists()

    fun create(password: CharArray): Opened {
        val salt = ByteArray(16).also(rnd::nextBytes)
        val params = KdfParams.DAILY_DEFAULT
        val dek = engine.randomBytes(32)
        val inner = PassphraseWrap.wrap(engine, password, salt, params, dek)
        val wrapped = outerWrap.wrap(inner)
        val vault = Vault()
        writeRaw(salt, params, wrapped, engine.aeadEncrypt(dek, VaultSerialization.encode(vault), vaultAad))
        return Opened(dek, vault)
    }

    /** Returns null if the password is wrong or the hardware key is unavailable. */
    fun unlock(password: CharArray): Opened? {
        val raw = readRaw()
        val inner = try {
            outerWrap.unwrap(raw.wrapped)
        } catch (e: Exception) {
            return null
        }
        val dek = try {
            PassphraseWrap.unwrap(engine, password, raw.salt, raw.params, inner)
        } catch (e: Exception) {
            return null
        }
        return openWithDek(dek, raw)
    }

    /** Re-encrypts the vault body with the in-session DEK; salt + wrapped-DEK are unchanged. */
    fun save(dek: ByteArray, vault: Vault) {
        val raw = readRaw()
        writeRaw(raw.salt, raw.params, raw.wrapped, engine.aeadEncrypt(dek, VaultSerialization.encode(vault), vaultAad))
    }

    /** Securely removes the vault and any biometric material (used by opt-in auto-wipe / reset). */
    fun wipe() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
        bioFile.delete()
    }

    // ---------------- Biometric quick-unlock ----------------

    fun hasBiometric(): Boolean = bioFile.exists()

    fun disableBiometric() {
        bioFile.delete()
    }

    /** Persists the DEK encrypted under the (already biometric-authorized) cipher. */
    fun enableBiometric(dek: ByteArray, authorizedEncryptCipher: Cipher) {
        val iv = authorizedEncryptCipher.iv
        val ct = authorizedEncryptCipher.doFinal(dek)
        val tmp = File(bioFile.parentFile, bioFile.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(iv.size); o.write(iv)
            o.writeInt(ct.size); o.write(ct)
        }
        if (!tmp.renameTo(bioFile)) {
            tmp.copyTo(bioFile, overwrite = true)
            tmp.delete()
        }
    }

    /** The IV needed to build the biometric decrypt cipher. */
    fun biometricIv(): ByteArray? {
        if (!bioFile.exists()) return null
        DataInputStream(bioFile.inputStream().buffered()).use { i ->
            return ByteArray(i.readInt()).also { i.readFully(it) }
        }
    }

    /** Recovers the DEK via the authorized biometric cipher and opens the vault. */
    fun unlockWithBiometric(authorizedDecryptCipher: Cipher): Opened? {
        if (!bioFile.exists()) return null
        val ct: ByteArray
        DataInputStream(bioFile.inputStream().buffered()).use { i ->
            val ivLen = i.readInt(); i.skipBytes(ivLen) // IV already used to init the cipher
            ct = ByteArray(i.readInt()).also { i.readFully(it) }
        }
        val dek = try {
            authorizedDecryptCipher.doFinal(ct)
        } catch (e: Exception) {
            return null
        }
        return openWithDek(dek, readRaw())
    }

    private fun openWithDek(dek: ByteArray, raw: Raw): Opened? {
        val vaultBytes = try {
            engine.aeadDecrypt(dek, raw.blob, vaultAad)
        } catch (e: Exception) {
            engine.zeroize(dek)
            return null
        }
        return Opened(dek, VaultSerialization.decode(vaultBytes))
    }

    private class Raw(val salt: ByteArray, val params: KdfParams, val wrapped: ByteArray, val blob: ByteArray)

    private fun writeRaw(salt: ByteArray, params: KdfParams, wrapped: ByteArray, blob: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(MAGIC)
            o.writeInt(FORMAT)
            o.writeInt(salt.size); o.write(salt)
            o.writeInt(params.memoryKib); o.writeInt(params.iterations); o.writeInt(params.parallelism)
            o.writeInt(wrapped.size); o.write(wrapped)
            o.writeInt(blob.size); o.write(blob)
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun readRaw(): Raw {
        DataInputStream(file.inputStream().buffered()).use { i ->
            require(i.readInt() == MAGIC) { "bad magic" }
            require(i.readInt() == FORMAT) { "unsupported format" }
            val salt = ByteArray(i.readInt()).also { i.readFully(it) }
            val params = KdfParams(i.readInt(), i.readInt(), i.readInt())
            val wrapped = ByteArray(i.readInt()).also { i.readFully(it) }
            val blob = ByteArray(i.readInt()).also { i.readFully(it) }
            return Raw(salt, params, wrapped, blob)
        }
    }

    private companion object {
        const val MAGIC = 0x504C4B31 // "PLK1"
        const val FORMAT = 2
    }
}
