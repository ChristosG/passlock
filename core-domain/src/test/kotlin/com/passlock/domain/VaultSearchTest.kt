package com.passlock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VaultSearchTest {
    private fun item(
        id: String, title: String, template: Template = Template.LOGIN,
        favorite: Boolean = false, tags: List<String> = emptyList(),
        fields: List<Field> = emptyList(),
    ) = Item(id, title, template, fields, tags, favorite, null, null, 0, 0)

    private val items = listOf(
        item("1", "Main Bank", Template.BANK, tags = listOf("finance")),
        item("2", "Email", favorite = true),
        item(
            "3", "Visa", Template.CREDIT_CARD,
            fields = listOf(Field("f1", "PIN", "4821", FieldType.PIN, isSecret = true)),
        ),
    )

    @Test
    fun `text matches title case-insensitively`() {
        assertEquals(listOf("1"), VaultSearch.filter(items, SearchQuery(text = "bank")).map { it.id })
    }

    @Test
    fun `favorites-only filters to favorites`() {
        assertEquals(listOf("2"), VaultSearch.filter(items, SearchQuery(favoritesOnly = true)).map { it.id })
    }

    @Test
    fun `template filter narrows by type`() {
        assertEquals(listOf("3"), VaultSearch.filter(items, SearchQuery(template = Template.CREDIT_CARD)).map { it.id })
    }

    @Test
    fun `does not match on secret field values`() {
        assertEquals(emptyList<String>(), VaultSearch.filter(items, SearchQuery(text = "4821")).map { it.id })
    }

    @Test
    fun `matches non-secret field values such as a VAT number`() {
        val withVat = items + item(
            "4", "Company", Template.CUSTOM,
            fields = listOf(Field("v", "VAT", "GB123456789", FieldType.TEXT, isSecret = false)),
        )
        assertEquals(listOf("4"), VaultSearch.filter(withVat, SearchQuery(text = "123456")).map { it.id })
        assertEquals(listOf("4"), VaultSearch.filter(withVat, SearchQuery(text = "vat")).map { it.id })
    }

    @Test
    fun `empty query returns everything`() {
        assertEquals(listOf("1", "2", "3"), VaultSearch.filter(items, SearchQuery()).map { it.id })
    }
}
