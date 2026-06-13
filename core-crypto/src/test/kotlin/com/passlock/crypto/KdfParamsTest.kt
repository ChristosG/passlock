package com.passlock.crypto

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KdfParamsTest {
    @Test
    fun `daily default meets floors`() {
        val p = KdfParams.DAILY_DEFAULT
        assert(p.memoryKib >= KdfParams.MIN_MEMORY_KIB)
        assert(p.iterations >= KdfParams.MIN_ITERATIONS)
        assert(p.parallelism >= 1)
    }

    @Test
    fun `backup default is stronger than daily`() {
        assert(KdfParams.BACKUP_DEFAULT.memoryKib >= KdfParams.DAILY_DEFAULT.memoryKib)
    }

    @Test
    fun `rejects parameters below the memory floor`() {
        assertThrows(IllegalArgumentException::class.java) {
            KdfParams(memoryKib = 1024, iterations = 3, parallelism = 1)
        }
    }
}
