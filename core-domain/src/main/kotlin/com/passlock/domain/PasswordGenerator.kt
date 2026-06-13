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
    fun generate(policy: PasswordPolicy): String {
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
        // guarantee one from each selected pool
        for (i in pools.indices) chars[i] = pools[i][random.nextInt(pools[i].length)]
        // fill the rest from the combined pool
        for (i in pools.size until policy.length) chars[i] = all[random.nextInt(all.length)]
        // Fisher-Yates shuffle so the guaranteed chars aren't positional
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
        }
        return String(chars)
    }
}
