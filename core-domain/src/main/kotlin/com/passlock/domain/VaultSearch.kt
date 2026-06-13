package com.passlock.domain

data class SearchQuery(
    val text: String = "",
    val template: Template? = null,
    val favoritesOnly: Boolean = false,
    val tag: String? = null,
)

object VaultSearch {
    fun filter(items: List<Item>, query: SearchQuery): List<Item> {
        val needle = query.text.trim().lowercase()
        return items.filter { item ->
            val textMatch = needle.isEmpty() ||
                item.title.lowercase().contains(needle) ||
                item.tags.any { it.lowercase().contains(needle) } ||
                // Match non-secret field labels AND their values (e.g. a VAT number),
                // but never the values of secret fields.
                item.fields.any { !it.isSecret && (it.label.lowercase().contains(needle) || it.value.lowercase().contains(needle)) }
            val templateMatch = query.template == null || item.template == query.template
            val favoriteMatch = !query.favoritesOnly || item.favorite
            val tagMatch = query.tag == null || item.tags.contains(query.tag)
            textMatch && templateMatch && favoriteMatch && tagMatch
        }
    }
}
