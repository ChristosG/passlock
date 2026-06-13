package com.passlock.data

import com.passlock.domain.Field
import com.passlock.domain.FieldType
import com.passlock.domain.Item
import com.passlock.domain.Template
import com.passlock.domain.Vault
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VaultStoreTest {

    @Test
    fun `create then unlock with the correct password recovers the vault`(@TempDir dir: File) {
        val store = VaultStore(dir)
        val created = store.create("correct horse battery".toCharArray())
        assertEquals(0, created.vault.items.size)

        val opened = store.unlock("correct horse battery".toCharArray())
        assertNotNull(opened)
        assertEquals(0, opened!!.vault.items.size)
    }

    @Test
    fun `wrong password cannot unlock (authentication-bypass prevented)`(@TempDir dir: File) {
        val store = VaultStore(dir)
        store.create("correct horse battery".toCharArray())
        assertNull(store.unlock("WRONG password here".toCharArray()))
    }

    @Test
    fun `saved items survive a re-open`(@TempDir dir: File) {
        val store = VaultStore(dir)
        val opened = store.create("correct horse battery".toCharArray())
        val vault = Vault(
            items = listOf(
                Item(
                    id = "1",
                    title = "Bank",
                    template = Template.CUSTOM,
                    fields = listOf(Field("f", "PIN", "4821", FieldType.PIN, isSecret = true)),
                    createdAt = 1,
                    updatedAt = 1,
                ),
            ),
        )
        store.save(opened.dek, vault)

        val reopened = store.unlock("correct horse battery".toCharArray())!!
        assertEquals(1, reopened.vault.items.size)
        assertEquals("Bank", reopened.vault.items[0].title)
        assertEquals("4821", reopened.vault.items[0].fields[0].value)
    }
}
