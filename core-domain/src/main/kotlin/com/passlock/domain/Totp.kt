package com.passlock.domain

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

enum class TotpAlgorithm(val macName: String) {
    SHA1("HmacSHA1"), SHA256("HmacSHA256"), SHA512("HmacSHA512")
}

/** RFC 6238 Time-based One-Time Passwords, computed on demand and never stored. */
object Totp {
    fun generate(
        secret: ByteArray,
        timeSeconds: Long,
        period: Int = 30,
        digits: Int = 6,
        algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    ): String {
        val counter = timeSeconds / period
        val msg = ByteArray(8)
        var v = counter
        for (i in 7 downTo 0) {
            msg[i] = (v and 0xff).toByte()
            v = v shr 8
        }
        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(secret, algorithm.macName))
        val hash = mac.doFinal(msg)
        val offset = (hash[hash.size - 1] and 0x0f).toInt()
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var pow = 1
        repeat(digits) { pow *= 10 }
        return (binary % pow).toString().padStart(digits, '0')
    }
}
