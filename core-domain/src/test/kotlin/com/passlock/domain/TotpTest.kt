package com.passlock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TotpTest {
    private val seed = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @Test
    fun `matches RFC 6238 vector at T=59`() {
        assertEquals("94287082", Totp.generate(seed, timeSeconds = 59, period = 30, digits = 8))
    }

    @Test
    fun `matches RFC 6238 vector at T=1111111109`() {
        assertEquals("07081804", Totp.generate(seed, timeSeconds = 1111111109, period = 30, digits = 8))
    }

    @Test
    fun `produces 6 digits by default`() {
        assertEquals(6, Totp.generate(seed, timeSeconds = 59).length)
    }
}
