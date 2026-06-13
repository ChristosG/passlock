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

/** A successfully-opened vault: the in-memory DEK plus the decrypted contents. */
class Opened(val dek: ByteArray, val vault: Vault)

/**
 * Encrypted vault persistence. The vault file holds:
 *   magic | format | salt | argon2 params | wrapped-DEK | AEAD(vault) under the DEK.
 * The DEK is wrapped by an Argon2id key derived from the master password; the vault
 * body is encrypted with the DEK. A wrong password fails AEAD authentication, so
 * unlocking is gated on real cryptographic verification — never a UI flag.
 *
 * NOTE: this is the password-only layer. The hardware-Keystore outer wrap and
 * biometric gating are added in a later task.
 */
class VaultStore(filesDir: File) {
    private val engine = BouncyCastleCryptoEngine()
    private val file = File(filesDir, "vault.plk")
    private val rnd = SecureRandom()
    private val vaultAad = "passlock.vault.v1".toByteArray()

    fun exists(): Boolean = file.exists()

    fun create(password: CharArray): Opened {
        val salt = ByteArray(16).also(rnd::nextBytes)
        val params = KdfParams.DAILY_DEFAULT
        val dek = engine.randomBytes(32)
        val wrapped = PassphraseWrap.wrap(engine, password, salt, params, dek)
        val vault = Vault()
        writeRaw(salt, params, wrapped, engine.aeadEncrypt(dek, VaultSerialization.encode(vault), vaultAad))
        return Opened(dek, vault)
    }

    /** Returns null if the password is wrong (AEAD authentication fails). */
    fun unlock(password: CharArray): Opened? {
        val raw = readRaw()
        val dek = try {
            PassphraseWrap.unwrap(engine, password, raw.salt, raw.params, raw.wrapped)
        } catch (e: Exception) {
            return null
        }
        val vaultBytes = try {
            engine.aeadDecrypt(dek, raw.blob, vaultAad)
        } catch (e: Exception) {
            engine.zeroize(dek)
            return null
        }
        return Opened(dek, VaultSerialization.decode(vaultBytes))
    }

    /** Re-encrypts the vault body with the in-session DEK; salt + wrapped-DEK are unchanged. */
    fun save(dek: ByteArray, vault: Vault) {
        val raw = readRaw()
        writeRaw(raw.salt, raw.params, raw.wrapped, engine.aeadEncrypt(dek, VaultSerialization.encode(vault), vaultAad))
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
        const val FORMAT = 1
    }
}
