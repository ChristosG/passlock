package com.passlock.data

import com.passlock.crypto.BouncyCastleCryptoEngine
import com.passlock.crypto.KdfParams
import com.passlock.domain.Field
import com.passlock.domain.FieldType
import com.passlock.domain.Item
import com.passlock.domain.Template
import com.passlock.domain.Vault
import com.passlock.domain.VaultSerialization
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class BackupTest {
    // Fast KDF for tests; production uses the hardened 256 MiB default (read back from the file).
    private val fast = KdfParams(memoryKib = 64 * 1024, iterations = 3, parallelism = 1)
    private val kit = ByteArray(16) { (it * 11 + 1).toByte() }

    private fun vaultOf(vararg items: Item) = Vault(items = items.toList())

    private val vault = vaultOf(
        Item(
            id = "1", title = "Visa", template = Template.CREDIT_CARD,
            fields = listOf(Field("f", "PIN", "4821", FieldType.PIN, isSecret = true)),
            createdAt = 1, updatedAt = 2,
        ),
    )

    @Test
    fun `passphrase-only export round-trips`() {
        val blob = Backup.export(vault, emptyMap(), "recovery passphrase here".toCharArray(), params = fast)
        val restored = Backup.import(blob, "recovery passphrase here".toCharArray())
        assertEquals(vault, restored?.vault)
    }

    @Test
    fun `wrong passphrase yields null`() {
        val blob = Backup.export(vault, emptyMap(), "recovery passphrase here".toCharArray(), params = fast)
        assertNull(Backup.import(blob, "WRONG passphrase".toCharArray()))
    }

    @Test
    fun `garbage input yields null`() {
        assertNull(Backup.import(ByteArray(8), "x".toCharArray()))
    }

    @Test
    fun `bundles and restores image blobs`() {
        val images = mapOf(
            "img-a" to byteArrayOf(1, 2, 3, 4, 5),
            "img-b" to ByteArray(256) { it.toByte() },
        )
        val blob = Backup.export(vault, images, "recovery passphrase here".toCharArray(), params = fast)
        val restored = Backup.import(blob, "recovery passphrase here".toCharArray())!!
        assertEquals(vault, restored.vault)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), restored.images["img-a"])
        assertArrayEquals(ByteArray(256) { it.toByte() }, restored.images["img-b"])
    }

    // ---------------- Recovery Kit (FORMAT 3) ----------------

    @Test
    fun `kit backup needs the right kit AND passphrase`() {
        val blob = Backup.export(vault, emptyMap(), "pass".toCharArray(), recoveryKey = kit, params = fast)
        assertTrue(Backup.peekNeedsKit(blob))
        assertEquals(vault, Backup.import(blob, "pass".toCharArray(), kit)?.vault)
    }

    @Test
    fun `kit backup fails with the wrong kit`() {
        val blob = Backup.export(vault, emptyMap(), "pass".toCharArray(), recoveryKey = kit, params = fast)
        val wrongKit = ByteArray(16) { 0 }
        assertNull(Backup.import(blob, "pass".toCharArray(), wrongKit))
    }

    @Test
    fun `kit backup fails when the kit is missing`() {
        val blob = Backup.export(vault, emptyMap(), "pass".toCharArray(), recoveryKey = kit, params = fast)
        assertNull(Backup.import(blob, "pass".toCharArray()))
    }

    @Test
    fun `kit backup fails with the wrong passphrase`() {
        val blob = Backup.export(vault, emptyMap(), "pass".toCharArray(), recoveryKey = kit, params = fast)
        assertNull(Backup.import(blob, "wrong".toCharArray(), kit))
    }

    @Test
    fun `passphrase-only backup does not need a kit`() {
        val blob = Backup.export(vault, emptyMap(), "pass".toCharArray(), params = fast)
        assertFalse(Backup.peekNeedsKit(blob))
    }

    // ---------------- Tamper-evidence ----------------

    @Test
    fun `a tampered header param makes the file refuse to decrypt`() {
        val blob = Backup.export(vault, emptyMap(), "pass".toCharArray(), params = fast)
        // Offset 36 lands in the Argon2 params region; flipping it simulates a cost downgrade.
        val tampered = blob.copyOf().also { it[36] = (it[36] + 1).toByte() }
        assertNull(Backup.import(tampered, "pass".toCharArray()))
    }

    // ---------------- Compression + padding ----------------

    @Test
    fun `large repetitive payloads round-trip through compression`() {
        val big = vaultOf(
            Item(
                id = "x", title = "Note", template = Template.SECURE_NOTE,
                fields = listOf(Field("n", "note", "A".repeat(20_000), FieldType.TEXT, isSecret = false)),
                createdAt = 1, updatedAt = 1,
            ),
        )
        val blob = Backup.export(big, emptyMap(), "pass".toCharArray(), params = fast)
        assertEquals(big, Backup.import(blob, "pass".toCharArray())?.vault)
        // Compression should make the file far smaller than the 20 KB of repeated text.
        assertTrue(blob.size < 10_000, "expected compressed file, got ${blob.size} bytes")
    }

    @Test
    fun `padding hides item count - different vaults produce the same file size`() {
        fun item(i: Int) = Item(
            id = "id$i", title = "t$i", template = Template.LOGIN,
            fields = listOf(Field("f$i", "user", "u$i", FieldType.TEXT, isSecret = false)),
            createdAt = 1, updatedAt = 1,
        )
        val small = Backup.export(vaultOf(item(1)), emptyMap(), "pass".toCharArray(), params = fast)
        val bigger = Backup.export(vaultOf(item(1), item(2), item(3), item(4)), emptyMap(), "pass".toCharArray(), params = fast)
        assertEquals(small.size, bigger.size, "ciphertext length should not leak how many items the vault holds")
    }

    // ---------------- Backward compatibility ----------------

    @Test
    fun `legacy FORMAT 1 backups still import`() {
        val engine = BouncyCastleCryptoEngine()
        val salt = ByteArray(16) { 7 }
        val key = engine.deriveKey("pw".toCharArray(), salt, fast)
        val blob = engine.aeadEncrypt(key, VaultSerialization.encode(vault), "passlock.backup.v1".toByteArray())
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(0x504C4B42); o.writeInt(1)
            o.writeInt(salt.size); o.write(salt)
            o.writeInt(fast.memoryKib); o.writeInt(fast.iterations); o.writeInt(fast.parallelism)
            o.writeInt(blob.size); o.write(blob)
        }
        val restored = Backup.import(bos.toByteArray(), "pw".toCharArray())
        assertNotNull(restored)
        assertEquals(vault, restored?.vault)
    }
}
