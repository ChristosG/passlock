package com.passlock.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TotpSecretTest {
    @Test
    fun `base32 decodes the RFC 4648 vector`() {
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW6YTBOI======"))
    }

    @Test
    fun `base32 ignores spaces and case`() {
        assertArrayEquals("foobar".toByteArray(), Base32.decode("mzxw 6ytb oi"))
    }

    @Test
    fun `parses a bare base32 secret`() {
        assertArrayEquals("foobar".toByteArray(), TotpSecret.parse("MZXW6YTBOI"))
    }

    @Test
    fun `parses the secret out of an otpauth URI`() {
        val uri = "otpauth://totp/Example:alice@x.com?secret=MZXW6YTBOI&issuer=Example&period=30"
        assertArrayEquals("foobar".toByteArray(), TotpSecret.parse(uri))
    }

    @Test
    fun `the parsed secret yields a stable TOTP code`() {
        val seed = TotpSecret.parse("MZXW6YTBOI")!!
        // Deterministic for a fixed time.
        assertEquals(Totp.generate(seed, 1_000_000), Totp.generate(seed, 1_000_000))
    }

    @Test
    fun `returns null for garbage`() {
        assertNull(TotpSecret.parse("!!! not base32 !!!"))
    }
}
