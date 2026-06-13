package com.passlock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VaultSerializationTest {
    @Test
    fun `round-trips an empty vault`() {
        val vault = Vault()
        assertEquals(vault, VaultSerialization.decode(VaultSerialization.encode(vault)))
    }

    @Test
    fun `round-trips a vault with items, fields, and tags`() {
        val vault = Vault(
            items = listOf(
                Item(
                    id = "i1",
                    title = "Visa",
                    template = Template.CREDIT_CARD,
                    fields = listOf(
                        Field("f1", "Card number", "4821000012341234", FieldType.NUMBER, isSecret = false),
                        Field("f2", "PIN", "4821", FieldType.PIN, isSecret = true),
                    ),
                    tags = listOf("finance", "cards"),
                    favorite = true,
                    primaryFieldId = "f2",
                    icon = "credit_card",
                    createdAt = 111,
                    updatedAt = 222,
                ),
                Item("i2", "Note", Template.SECURE_NOTE, emptyList(), createdAt = 1, updatedAt = 2),
            ),
        )
        assertEquals(vault, VaultSerialization.decode(VaultSerialization.encode(vault)))
    }
}
