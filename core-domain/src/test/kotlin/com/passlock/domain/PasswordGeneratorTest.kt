package com.passlock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PasswordGeneratorTest {
    private val gen = PasswordGenerator()

    @Test
    fun `respects requested length`() {
        val pw = gen.generate(PasswordPolicy(length = 24))
        assertEquals(24, pw.length)
    }

    @Test
    fun `digits-only policy produces only digits`() {
        val pin = gen.generate(PasswordPolicy(length = 6, lower = false, upper = false, digits = true, symbols = false))
        assertEquals(6, pin.length)
        assert(pin.all { it.isDigit() })
    }

    @Test
    fun `includes at least one of every selected class`() {
        repeat(50) {
            val pw = gen.generate(PasswordPolicy(length = 8))
            assert(pw.any { it.isLowerCase() })
            assert(pw.any { it.isUpperCase() })
            assert(pw.any { it.isDigit() })
            assert(pw.any { !it.isLetterOrDigit() })
        }
    }

    @Test
    fun `rejects a policy with no character classes`() {
        assertThrows(IllegalArgumentException::class.java) {
            gen.generate(PasswordPolicy(length = 8, lower = false, upper = false, digits = false, symbols = false))
        }
    }
}
