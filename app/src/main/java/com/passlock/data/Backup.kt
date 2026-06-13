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

/** Result of importing a backup: the vault plus its decrypted image blobs (keyed by id). */
class Restored(val vault: Vault, val images: Map<String, ByteArray>)

/**
 * Portable, encrypted vault backup — now bundling image attachments so they survive
 * a restore (the old per-device blobs can't be decrypted on a new device/key).
 *
 * Encrypted with a key derived from a SEPARATE recovery passphrase (strong Argon2id).
 * Format: magic | format | salt | argon2 params | AEAD(bundle), where the bundle is
 * the serialized vault followed by each image's decrypted bytes.
 */
object Backup {
    private const val MAGIC = 0x504C4B42 // "PLKB"
    private const val FORMAT = 2 // 1 = vault only (legacy); 2 = vault + image blobs
    private val AAD = "passlock.backup.v1".toByteArray()
    private val engine = BouncyCastleCryptoEngine()
    private val rnd = SecureRandom()

    fun export(vault: Vault, images: Map<String, ByteArray>, passphrase: CharArray): ByteArray {
        val salt = ByteArray(16).also(rnd::nextBytes)
        val params = KdfParams.BACKUP_DEFAULT
        val key = engine.deriveKey(passphrase, salt, params)
        try {
            val blob = engine.aeadEncrypt(key, encodeBundle(vault, images), AAD)
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(FORMAT)
                out.writeInt(salt.size); out.write(salt)
                out.writeInt(params.memoryKib); out.writeInt(params.iterations); out.writeInt(params.parallelism)
                out.writeInt(blob.size); out.write(blob)
            }
            return bos.toByteArray()
        } finally {
            engine.zeroize(key)
        }
    }

    /** Returns null if the passphrase is wrong or the file is not a valid backup. */
    fun import(bytes: ByteArray, passphrase: CharArray): Restored? {
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { i ->
                if (i.readInt() != MAGIC) return null
                val format = i.readInt()
                if (format !in 1..FORMAT) return null
                val salt = ByteArray(i.readInt()).also { i.readFully(it) }
                val params = KdfParams(i.readInt(), i.readInt(), i.readInt())
                val blob = ByteArray(i.readInt()).also { i.readFully(it) }
                val key = engine.deriveKey(passphrase, salt, params)
                try {
                    val plain = engine.aeadDecrypt(key, blob, AAD)
                    if (format == 1) Restored(VaultSerialization.decode(plain), emptyMap()) else decodeBundle(plain)
                } finally {
                    engine.zeroize(key)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun encodeBundle(vault: Vault, images: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            val vaultBytes = VaultSerialization.encode(vault)
            out.writeInt(vaultBytes.size); out.write(vaultBytes)
            out.writeInt(images.size)
            for ((id, data) in images) {
                out.writeUTF(id)
                out.writeInt(data.size); out.write(data)
            }
        }
        return bos.toByteArray()
    }

    private fun decodeBundle(bytes: ByteArray): Restored {
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            val vaultBytes = ByteArray(i.readInt()).also { i.readFully(it) }
            val vault = VaultSerialization.decode(vaultBytes)
            val count = i.readInt()
            val images = HashMap<String, ByteArray>(count)
            repeat(count) {
                val id = i.readUTF()
                images[id] = ByteArray(i.readInt()).also { i.readFully(it) }
            }
            return Restored(vault, images)
        }
    }
}
