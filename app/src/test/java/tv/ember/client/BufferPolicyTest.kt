package tv.ember.client

import org.junit.Test
import org.junit.Assert.*
import tv.ember.client.player.*
import tv.ember.client.settings.*

class BufferPolicyTest {
    @Test fun allRequestedCombinationsAreValidAndSafe() {
        for(mode in BufferMode.entries) for(size in listOf(512, 1024, 2048)) for(pre in listOf(5, 15, 30, 60)) {
            val p = BufferPolicy.create(BufferPreferences(mode, size, pre), 256L * 1024 * 1024, 48L * 1024 * 1024)
            assertTrue(p.targetBytes in 1..(154 * 1024 * 1024))
            assertTrue(p.minMs >= p.startMs); assertTrue(p.maxMs >= p.minMs); assertTrue(p.rebufferMs <= p.minMs)
            assertEquals(pre * 1000, p.startMs)
        }
    }
    @Test fun twoGbSelectionDoesNotOverflow() {
        val p = BufferPolicy.create(BufferPreferences(BufferMode.LARGE, 2048, 60), 16L * 1024 * 1024 * 1024)
        assertTrue(p.targetBytes > 0); assertTrue(p.targetBytes < Int.MAX_VALUE)
    }
    @Test fun memoryPressureReducesBudget() {
        val prefs = BufferPreferences()
        val normal = BufferPolicy.create(prefs, 256L * 1024 * 1024)
        val low = BufferPolicy.create(prefs, 256L * 1024 * 1024, lowMemory = true)
        val occupied = BufferPolicy.create(prefs, 256L * 1024 * 1024, 240L * 1024 * 1024)
        assertTrue(low.targetBytes < normal.targetBytes); assertTrue(occupied.targetBytes < low.targetBytes)
    }
    @Test fun modesChangeActualBufferDurations() {
        val low = BufferPolicy.create(BufferPreferences(BufferMode.LOW_LATENCY), 1L shl 30)
        val big = BufferPolicy.create(BufferPreferences(BufferMode.LARGE), 1L shl 30)
        assertTrue(big.maxMs > low.maxMs); assertTrue(big.minMs > low.minMs)
    }
    @Test fun retriesBackOffAndDoNotRetryPermanentFailures() {
        assertEquals(1000L, RetryPolicy.delayMs(0)); assertEquals(30000L, RetryPolicy.delayMs(20))
        assertTrue(RetryPolicy.retryableHttp(503)); assertTrue(RetryPolicy.retryableHttp(429))
        for(code in listOf(401,403,404,410)) assertFalse(RetryPolicy.retryableHttp(code))
    }
    @Test fun highBitrateUses64To128MiBWithReservedHeapHeadroom() {
        val mib=1048576L
        for(disk in listOf(false,true)) for(heap in listOf(256L,384L,512L)) {
            val policy=BufferPolicy.create(BufferPreferences(),heap*mib,64*mib,bitrate=80_000_000,diskBuffering=disk)
            assertTrue(policy.targetBytes in (64*mib).toInt()..(128*mib).toInt())
            assertTrue(policy.targetBytes+64*mib+16*mib+48*mib<heap*mib)
        }
        val cramped=BufferPolicy.create(BufferPreferences(),128*mib,64*mib,bitrate=80_000_000)
        assertTrue(cramped.targetBytes<64*mib)
    }
}
