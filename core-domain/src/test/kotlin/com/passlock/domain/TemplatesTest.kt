package com.passlock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class TemplatesTest {
    private val counter = AtomicInteger(0)
    private val idGen = { "f${counter.incrementAndGet()}" }

    @Test
    fun `credit card template has the expected fields with secrets marked`() {
        val fields = Templates.defaultFields(Template.CREDIT_CARD, idGen)
        assertEquals(listOf("Card number", "Expiry", "CVV", "PIN", "Cardholder"), fields.map { it.label })
        assertTrue(fields.first { it.label == "CVV" }.isSecret)
        assertTrue(fields.first { it.label == "PIN" }.isSecret)
        assertTrue(!fields.first { it.label == "Card number" }.isSecret)
    }

    @Test
    fun `custom template starts empty`() {
        assertEquals(emptyList<Field>(), Templates.defaultFields(Template.CUSTOM, idGen))
    }
}
