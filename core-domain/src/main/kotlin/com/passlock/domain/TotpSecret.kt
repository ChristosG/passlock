package com.passlock.domain

import java.io.ByteArrayOutputStream

/** RFC 4648 Base32 decoding (used for TOTP secrets). */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(input: String): ByteArray {
        val s = input.trim().replace(" ", "").replace("-", "").uppercase().trimEnd('=')
        if (s.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (c in s) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "invalid base32 character: $c" }
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write((buffer shr bitsLeft) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}

/**
 * Parses a stored TOTP secret value into raw key bytes. Accepts either a bare
 * Base32 secret (with optional spaces/dashes) or a full `otpauth://` URI.
 * Returns null if it can't be parsed.
 */
object TotpSecret {
    private val SECRET_PARAM = Regex("[?&]secret=([^&]+)", RegexOption.IGNORE_CASE)

    fun parse(value: String): ByteArray? {
        val v = value.trim()
        val secret = if (v.startsWith("otpauth://", ignoreCase = true)) {
            SECRET_PARAM.find(v)?.groupValues?.get(1)
        } else {
            v
        }
        if (secret.isNullOrBlank()) return null
        return try {
            Base32.decode(secret).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
