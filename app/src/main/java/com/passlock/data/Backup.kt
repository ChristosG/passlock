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
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Result of importing a backup: the vault plus its decrypted image blobs (keyed by id). */
class Restored(val vault: Vault, val images: Map<String, ByteArray>)

/**
 * Portable, encrypted vault backup.
 *
 * The key is derived from a recovery passphrase AND — for FORMAT 3 — a high-entropy 128-bit
 * Recovery Kit mixed in as Argon2's "secret" (pepper). The kit is never written to the file,
 * so an attacker who steals the file faces a 2^128 wall even with a weak passphrase and the
 * full source. The full header (salt + Argon2 params + flags) is bound as AEAD AAD, so the
 * cost parameters can't be silently downgraded. The plaintext is Deflate-compressed and padded
 * to a 4 KiB bucket so the ciphertext length doesn't reveal the vault's size.
 *
 *   magic | format | flags | salt | argon2 params | AEAD(pad(len ‖ deflate(bundle)), AAD=header)
 *
 * where bundle = serialized vault followed by each image's decrypted bytes.
 */
object Backup {
    private const val MAGIC = 0x504C4B42 // "PLKB"
    private const val FORMAT = 3 // 1=vault only; 2=vault+images (passphrase only); 3=+kit, AAD-bound, compressed
    private const val FLAG_HAS_KIT = 0x01
    private const val PAD_BUCKET = 4096
    private val LEGACY_AAD = "passlock.backup.v1".toByteArray() // formats 1 & 2
    private val engine = BouncyCastleCryptoEngine()
    private val rnd = SecureRandom()

    /**
     * Encrypts a backup. [recoveryKey] (16 bytes) is the optional Recovery Kit; when null/empty
     * the backup is passphrase-only. [params] is overridable for tests; production uses the
     * hardened default.
     */
    fun export(
        vault: Vault,
        images: Map<String, ByteArray>,
        passphrase: CharArray,
        recoveryKey: ByteArray? = null,
        params: KdfParams = KdfParams.BACKUP_DEFAULT,
    ): ByteArray {
        val salt = ByteArray(16).also(rnd::nextBytes)
        val hasKit = recoveryKey != null && recoveryKey.isNotEmpty()
        val flags = if (hasKit) FLAG_HAS_KIT else 0
        val header = buildHeader(flags, salt, params)
        val key = engine.deriveKey(passphrase, salt, params, recoveryKey ?: ByteArray(0))
        try {
            val payload = packPayload(encodeBundle(vault, images))
            val blob = engine.aeadEncrypt(key, payload, header)
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { out ->
                out.write(header)
                out.writeInt(blob.size); out.write(blob)
            }
            return bos.toByteArray()
        } finally {
            engine.zeroize(key)
        }
    }

    /** True if this file was sealed with a Recovery Kit (so restore must ask for one). */
    fun peekNeedsKit(bytes: ByteArray): Boolean = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            if (i.readInt() != MAGIC) false
            else if (i.readInt() < 3) false
            else (i.readUnsignedByte() and FLAG_HAS_KIT) != 0
        }
    } catch (e: Exception) {
        false
    }

    /** Returns null if the passphrase/kit is wrong or the file is not a valid backup. */
    fun import(bytes: ByteArray, passphrase: CharArray, recoveryKey: ByteArray? = null): Restored? {
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { i ->
                if (i.readInt() != MAGIC) return null
                val format = i.readInt()
                if (format !in 1..FORMAT) return null
                if (format <= 2) return importLegacy(i, format, passphrase)

                val flags = i.readUnsignedByte()
                val salt = ByteArray(i.readInt()).also { i.readFully(it) }
                val params = KdfParams(i.readInt(), i.readInt(), i.readInt())
                val blob = ByteArray(i.readInt()).also { i.readFully(it) }
                val header = buildHeader(flags, salt, params)
                val secret = if (flags and FLAG_HAS_KIT != 0) (recoveryKey ?: return null) else ByteArray(0)
                val key = engine.deriveKey(passphrase, salt, params, secret)
                try {
                    val payload = engine.aeadDecrypt(key, blob, header) // throws on wrong key/kit/tamper
                    decodeBundle(unpackPayload(payload))
                } finally {
                    engine.zeroize(key)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Legacy formats (1, 2) used a constant AAD and no compression or kit. */
    private fun importLegacy(i: DataInputStream, format: Int, passphrase: CharArray): Restored? {
        val salt = ByteArray(i.readInt()).also { i.readFully(it) }
        val params = KdfParams(i.readInt(), i.readInt(), i.readInt())
        val blob = ByteArray(i.readInt()).also { i.readFully(it) }
        val key = engine.deriveKey(passphrase, salt, params)
        return try {
            val plain = engine.aeadDecrypt(key, blob, LEGACY_AAD)
            if (format == 1) Restored(VaultSerialization.decode(plain), emptyMap()) else decodeBundle(plain)
        } finally {
            engine.zeroize(key)
        }
    }

    private fun buildHeader(flags: Int, salt: ByteArray, params: KdfParams): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT)
            out.writeByte(flags)
            out.writeInt(salt.size); out.write(salt)
            out.writeInt(params.memoryKib); out.writeInt(params.iterations); out.writeInt(params.parallelism)
        }
        return bos.toByteArray()
    }

    // ---- payload framing: compress, length-prefix, pad to a bucket (hides vault size) ----

    private fun packPayload(bundle: ByteArray): ByteArray {
        val deflated = deflate(bundle)
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(deflated.size); out.write(deflated)
        }
        val framed = bos.toByteArray()
        val target = ((framed.size + PAD_BUCKET - 1) / PAD_BUCKET) * PAD_BUCKET
        return framed.copyOf(target) // zero-padded; padding is ignored on read
    }

    private fun unpackPayload(payload: ByteArray): ByteArray {
        DataInputStream(ByteArrayInputStream(payload)).use { i ->
            val deflated = ByteArray(i.readInt()).also { i.readFully(it) }
            return inflate(deflated)
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(data); finish() }
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) bos.write(buf, 0, d.deflate(buf))
        d.end()
        return bos.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater().apply { setInput(data) }
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && inf.needsInput()) break
            bos.write(buf, 0, n)
        }
        inf.end()
        return bos.toByteArray()
    }

    // ---- bundle = serialized vault + each image blob (length-prefixed) ----

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
