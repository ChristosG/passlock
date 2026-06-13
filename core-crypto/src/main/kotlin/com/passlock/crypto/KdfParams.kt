package com.passlock.crypto

/** Parameters for the Argon2id key-derivation function, with safety floors. */
data class KdfParams(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    init {
        require(memoryKib >= MIN_MEMORY_KIB) { "memoryKib $memoryKib below floor $MIN_MEMORY_KIB" }
        require(iterations >= MIN_ITERATIONS) { "iterations $iterations below floor $MIN_ITERATIONS" }
        require(parallelism >= 1) { "parallelism must be >= 1" }
    }

    companion object {
        const val MIN_MEMORY_KIB = 64 * 1024   // 64 MiB floor
        const val MIN_ITERATIONS = 3

        /** Conservative defaults for daily unlock; calibrate per device at runtime. */
        val DAILY_DEFAULT = KdfParams(memoryKib = 64 * 1024, iterations = 3, parallelism = 1)

        /**
         * Stronger params for backups, which live in riskier locations and grant an attacker
         * unlimited offline guesses. 128 MiB / t=4 — a device-safe amount (256 MiB risks OOM on
         * phones whose app heap is ~256 MB, especially while images are also decrypted in memory
         * during export). Defense-in-depth behind the Recovery Kit, never a substitute for it.
         */
        val BACKUP_DEFAULT = KdfParams(memoryKib = 128 * 1024, iterations = 4, parallelism = 1)
    }
}
