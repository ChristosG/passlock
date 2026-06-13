package com.passlock.domain

enum class FieldType { TEXT, PASSWORD, PIN, NUMBER, TOTP_SEED }

enum class Template { LOGIN, CREDIT_CARD, BANK, SECURE_NOTE, CUSTOM }

data class Field(
    val id: String,
    val label: String,
    val value: String,
    val type: FieldType,
    val isSecret: Boolean,
    /** If true, revealing or copying this field requires a fresh biometric auth. */
    val requireBiometric: Boolean = false,
)

data class Item(
    val id: String,
    val title: String,
    val template: Template,
    val fields: List<Field>,
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val primaryFieldId: String? = null,
    val icon: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Ids of encrypted image attachments stored as separate blobs. */
    val attachments: List<String> = emptyList(),
)

data class Vault(
    val items: List<Item> = emptyList(),
    /** Standalone gallery images (encrypted blob ids) not attached to any item. */
    val galleryImages: List<String> = emptyList(),
)
