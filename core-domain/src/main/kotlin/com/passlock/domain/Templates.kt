package com.passlock.domain

/** Default starting fields for each template. Every field stays editable after creation. */
object Templates {
    fun defaultFields(template: Template, idGen: () -> String): List<Field> = when (template) {
        Template.LOGIN -> listOf(
            Field(idGen(), "Username", "", FieldType.TEXT, isSecret = false),
            Field(idGen(), "Password", "", FieldType.PASSWORD, isSecret = true),
            Field(idGen(), "URL", "", FieldType.TEXT, isSecret = false),
        )
        Template.CREDIT_CARD -> listOf(
            Field(idGen(), "Card number", "", FieldType.NUMBER, isSecret = false),
            Field(idGen(), "Expiry", "", FieldType.TEXT, isSecret = false),
            Field(idGen(), "CVV", "", FieldType.PIN, isSecret = true),
            Field(idGen(), "PIN", "", FieldType.PIN, isSecret = true),
            Field(idGen(), "Cardholder", "", FieldType.TEXT, isSecret = false),
        )
        Template.BANK -> listOf(
            Field(idGen(), "Account", "", FieldType.NUMBER, isSecret = false),
            Field(idGen(), "IBAN", "", FieldType.TEXT, isSecret = false),
            Field(idGen(), "PIN", "", FieldType.PIN, isSecret = true),
        )
        Template.SECURE_NOTE -> listOf(
            Field(idGen(), "Note", "", FieldType.TEXT, isSecret = true),
        )
        Template.CUSTOM -> emptyList()
    }
}
