package com.passlock.crypto

import java.util.zip.CRC32

/**
 * Encodes a 128-bit recovery secret as a grouped Crockford-Base32 string with a checksum,
 * e.g. "K7QF-2M9X-4ABD-8RT2-VH3K-PW5G-TN2J". Pure stdlib — no dependencies.
 *
 * The secret is the high-entropy half of a backup's key (the other half is the user's
 * passphrase). It is generated fresh per export, shown once, and never stored in the file.
 *
 * A 10-bit CRC32 checksum catches transcription typos before any decrypt is attempted.
 * Decoding is lenient about case, dashes/spaces, and the classic O/0 and I/L/1 confusions.
 */
object RecoveryKit {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford (no I L O U)
    const val SECRET_BYTES = 16                                     // 128-bit
    private const val PAYLOAD_CHARS = 26                            // ceil(128 / 5)
    private const val TOTAL_CHARS = PAYLOAD_CHARS + 2               // + 2 checksum chars (10 bits)

    fun encode(secret: ByteArray): String {
        require(secret.size == SECRET_BYTES) { "recovery secret must be $SECRET_BYTES bytes" }
        val raw = base32Encode(secret, PAYLOAD_CHARS) + checksumChars(secret)
        return raw.chunked(4).joinToString("-")
    }

    /** Returns the 16-byte secret, or null if the format or checksum is invalid. */
    fun decode(text: String): ByteArray? {
        val norm = normalize(text)
        if (norm.length != TOTAL_CHARS) return null
        val secret = base32Decode(norm.substring(0, PAYLOAD_CHARS)) ?: return null
        return if (norm.substring(PAYLOAD_CHARS) == checksumChars(secret)) secret else null
    }

    private fun normalize(text: String): String = buildString {
        for (c in text.uppercase()) {
            when (c) {
                'O' -> append('0')
                'I', 'L' -> append('1')
                '-', ' ' -> {} // skip separators
                else -> if (c in ALPHABET) append(c) else return@buildString
            }
        }
    }

    /** Low 10 bits of CRC32 over the secret, as two Base32 chars. */
    private fun checksumChars(secret: ByteArray): String {
        val low10 = (CRC32().apply { update(secret) }.value and 0x3FF).toInt()
        return "${ALPHABET[(low10 ushr 5) and 0x1F]}${ALPHABET[low10 and 0x1F]}"
    }

    private fun base32Encode(data: ByteArray, nChars: Int): String {
        val sb = StringBuilder(nChars)
        var buffer = 0L
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[((buffer ushr bits) and 0x1F).toInt()])
            }
            buffer = buffer and ((1L shl bits) - 1L)
        }
        if (bits > 0) sb.append(ALPHABET[((buffer shl (5 - bits)) and 0x1F).toInt()])
        while (sb.length < nChars) sb.append(ALPHABET[0])
        return sb.substring(0, nChars)
    }

    /** Decodes Base32 chars back to bytes (drops trailing pad bits). Null on an invalid char. */
    private fun base32Decode(text: String): ByteArray? {
        val out = ByteArray(SECRET_BYTES)
        var buffer = 0L
        var bits = 0
        var pos = 0
        for (c in text) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                if (pos < SECRET_BYTES) out[pos++] = ((buffer ushr bits) and 0xFF).toByte()
                buffer = buffer and ((1L shl bits) - 1L)
            }
        }
        return if (pos == SECRET_BYTES) out else null
    }
}
