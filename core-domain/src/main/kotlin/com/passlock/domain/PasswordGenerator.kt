package com.passlock.domain

import java.security.SecureRandom

data class PasswordPolicy(
    val length: Int = 20,
    val lower: Boolean = true,
    val upper: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
) {
    init { require(length >= 4) { "length must be >= 4" } }
}

class PasswordGenerator(private val random: SecureRandom = SecureRandom()) {

    /** Convenience wrapper. Note: the returned String cannot be wiped from memory. */
    fun generate(policy: PasswordPolicy): String = String(generateChars(policy))

    /**
     * Secret-safe generation: returns the password as a CharArray.
     * The CALLER owns the array and should zeroize it (e.g. `array.fill(' ')`)
     * once the value has been consumed.
     */
    fun generateChars(policy: PasswordPolicy): CharArray {
        val pools = buildList {
            if (policy.lower) add("abcdefghijklmnopqrstuvwxyz")
            if (policy.upper) add("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            if (policy.digits) add("0123456789")
            if (policy.symbols) add("!@#\$%^&*()-_=+[]{};:,.?")
        }
        require(pools.isNotEmpty()) { "at least one character class required" }
        require(policy.length >= pools.size) { "length too short to include every class" }

        val all = pools.joinToString("")
        val chars = CharArray(policy.length)
        for (i in pools.indices) chars[i] = pools[i][random.nextInt(pools[i].length)]
        for (i in pools.size until policy.length) chars[i] = all[random.nextInt(all.length)]
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
        }
        return chars
    }
}
