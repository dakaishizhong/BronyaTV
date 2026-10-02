package tv.ember.client.player

import tv.ember.client.settings.BufferMode
import tv.ember.client.settings.BufferPreferences

/** A selectable ceiling, never a reservation. No disk or offline cache. */
data class BufferPolicy(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int, val targetBytes: Int) {
    companion object {
        fun create(p: BufferPreferences, heapMax: Long, usedHeap: Long = 0, lowMemory: Boolean = false): BufferPolicy {
            val requested = p.requestedMb.toLong() * 1024 * 1024 // 2 GB must not overflow an Int.
            val remaining = (heapMax - usedHeap).coerceAtLeast(0)
            val safety = minOf(heapMax / (if (lowMemory) 8 else 4), remaining / 3)
            val bytes = minOf(requested, safety, (Int.MAX_VALUE - 65_536).toLong()).coerceAtLeast(1_048_576).toInt()
            val pre = p.prebufferSeconds.coerceIn(5, 60) * 1000
            val min = maxOf(pre, when(p.mode) { BufferMode.LOW_LATENCY -> 10_000; BufferMode.BALANCED -> 30_000; BufferMode.LARGE -> 90_000; BufferMode.AUTO -> if(lowMemory) 15_000 else 45_000 })
            val max = maxOf(min, when(p.mode) { BufferMode.LOW_LATENCY -> 20_000; BufferMode.BALANCED -> 90_000; BufferMode.LARGE -> 300_000; BufferMode.AUTO -> if(lowMemory) 45_000 else 120_000 })
            return BufferPolicy(min, max, pre, minOf(pre, 15_000), bytes)
        }
    }
}
