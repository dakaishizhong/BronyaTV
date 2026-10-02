package tv.ember.client.player

import tv.ember.client.settings.BufferMode
import tv.ember.client.settings.BufferPreferences

/** A selectable ceiling, never a reservation. No disk or offline cache. */
data class BufferPolicy(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int, val targetBytes: Int, val backBufferMs: Int = 0) {
    companion object {
        fun create(p: BufferPreferences, heapMax: Long, usedHeap: Long = 0, lowMemory: Boolean = false, bitrate: Long = 0): BufferPolicy {
            val requested = if(p.requestedMb==0) 96L*1024*1024 else p.requestedMb.toLong() * 1024 * 1024
            val remaining = (heapMax - usedHeap).coerceAtLeast(0)
            val safety = minOf(heapMax / (if (lowMemory) 8 else 4), remaining / 3)
            val bytes = minOf(requested, safety, (Int.MAX_VALUE - 65_536).toLong()).coerceAtLeast(1_048_576).toInt()
            val pre = p.prebufferSeconds.coerceIn(2, 60) * 1000
            val min = maxOf(pre, when(p.mode) { BufferMode.LOW_LATENCY -> 10_000; BufferMode.BALANCED -> 30_000; BufferMode.LARGE -> 90_000; BufferMode.AUTO -> if(lowMemory) 15_000 else 45_000 })
            val max = maxOf(min, when(p.mode) { BufferMode.LOW_LATENCY -> 20_000; BufferMode.BALANCED -> 90_000; BufferMode.LARGE -> 300_000; BufferMode.AUTO -> if(lowMemory) 45_000 else 120_000 })
            // Backward seek retention shares the same allocator and memory ceiling.
            val back = if(lowMemory || bitrate<=0) 0 else
                minOf(p.backBufferSeconds.coerceIn(0,30)*1000L,bytes.toLong()/4*8000/bitrate).toInt().let { if(it<1000) 0 else it }
            return BufferPolicy(min, max, pre, minOf(pre, 5_000), bytes, back)
        }
    }
}
