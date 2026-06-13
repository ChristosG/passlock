package com.passlock.data

import com.passlock.crypto.BouncyCastleCryptoEngine
import com.passlock.crypto.KdfParams
import com.passlock.domain.Vault
import com.passlock.domain.VaultSerialization
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom

/**
 * Portable, encrypted vault backup.
 *
 * The backup is the only artefact that leaves the device's secure element, so it
 * cannot use the hardware key (it must restore on a new phone). It is encrypted with
 * a key derived from a SEPARATE recovery passphrase using stronger Argon2id parameters.
 * Format: magic | format | salt | argon2 params | AEAD(serialized vault).
 */
object Backup {
    private const val MAGIC = 0x504C4B42 // "PLKB"
    private const val FORMAT = 1
    private val AAD = "passlock.backup.v1".toByteArray()
    private val engine = BouncyCastleCryptoEngine()
    private val rnd = SecureRandom()

    fun export(vault: Vault, passphrase: CharArray): ByteArray {
        val salt = ByteArray(16).also(rnd::nextBytes)
        val params = KdfParams.BACKUP_DEFAULT
        val key = engine.deriveKey(passphrase, salt, params)
        try {
            val blob = engine.aeadEncrypt(key, VaultSerialization.encode(vault), AAD)
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(FORMAT)
                o.writeInt(salt.size); o.write(salt)
                o.writeInt(params.memoryKib); o.writeInt(params.iterations); o.writeInt(params.parallelism)
                o.writeInt(blob.size); o.write(blob)
            }
            return bos.toByteArray()
        } finally {
            engine.zeroize(key)
        }
    }

    /** Returns null if the passphrase is wrong or the file is not a valid backup. */
    fun import(bytes: ByteArray, passphrase: CharArray): Vault? {
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { i ->
                if (i.readInt() != MAGIC || i.readInt() != FORMAT) return null
                val salt = ByteArray(i.readInt()).also { i.readFully(it) }
                val params = KdfParams(i.readInt(), i.readInt(), i.readInt())
                val blob = ByteArray(i.readInt()).also { i.readFully(it) }
                val key = engine.deriveKey(passphrase, salt, params)
                try {
                    VaultSerialization.decode(engine.aeadDecrypt(key, blob, AAD))
                } finally {
                    engine.zeroize(key)
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
