package com.passlock.data

import com.passlock.domain.Field
import com.passlock.domain.FieldType
import com.passlock.domain.Item
import com.passlock.domain.Template
import com.passlock.domain.Vault
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BackupTest {
    private val vault = Vault(
        items = listOf(
            Item(
                id = "1", title = "Visa", template = Template.CREDIT_CARD,
                fields = listOf(Field("f", "PIN", "4821", FieldType.PIN, isSecret = true)),
                createdAt = 1, updatedAt = 2,
            ),
        ),
    )

    @Test
    fun `export then import with the recovery passphrase round-trips`() {
        val blob = Backup.export(vault, emptyMap(), "recovery passphrase here".toCharArray())
        val restored = Backup.import(blob, "recovery passphrase here".toCharArray())
        assertEquals(vault, restored?.vault)
    }

    @Test
    fun `wrong recovery passphrase yields null`() {
        val blob = Backup.export(vault, emptyMap(), "recovery passphrase here".toCharArray())
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
        val blob = Backup.export(vault, images, "recovery passphrase here".toCharArray())
        val restored = Backup.import(blob, "recovery passphrase here".toCharArray())!!
        assertEquals(vault, restored.vault)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), restored.images["img-a"])
        assertArrayEquals(ByteArray(256) { it.toByte() }, restored.images["img-b"])
    }
}
