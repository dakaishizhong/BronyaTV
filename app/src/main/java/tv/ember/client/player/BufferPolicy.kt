package tv.ember.client.player

import tv.ember.client.settings.BufferMode
import tv.ember.client.settings.BufferPreferences

/** A selectable memory target; load control allows a bounded recovery reserve. No disk cache. */
data class BufferPolicy(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int, val targetBytes: Int, val backBufferMs: Int = 0) {
    companion object {
        fun create(p: BufferPreferences, heapMax: Long, usedHeap: Long = 0, lowMemory: Boolean = false, bitrate: Long = 0, diskBuffering: Boolean = false): BufferPolicy {
            val mib = 1024L * 1024
            val requested = if(p.requestedMb==0) (if(bitrate >= 48_000_000) 128L else if(diskBuffering) 64L else 96L)*mib else p.requestedMb.toLong() * mib
            val remaining = (heapMax - usedHeap).coerceAtLeast(0)
            // Reserve space for UI/decoder growth, both range queues (at most 16 MiB),
            // and allocator overshoot. The live heap already includes existing UI allocations.
            val headroom = maxOf(48*mib, heapMax/5) + 16*mib + 8*mib
            val safety = if(lowMemory) minOf(heapMax/8, (remaining-headroom).coerceAtLeast(0))
                else minOf(heapMax*3/5, (remaining-headroom).coerceAtLeast(0))
            val bytes = minOf(requested, safety, (Int.MAX_VALUE - 65_536).toLong()).coerceAtLeast(1_048_576).toInt()
            val highBitrate = p.mode == BufferMode.AUTO && bitrate >= 24_000_000 && !lowMemory
            val pre = maxOf(p.prebufferSeconds.coerceIn(2, 60) * 1000, if(highBitrate) 5000 else 2000)
            val min = maxOf(pre, when(p.mode) { BufferMode.LOW_LATENCY -> 10_000; BufferMode.BALANCED -> 30_000; BufferMode.LARGE -> 90_000; BufferMode.AUTO -> if(lowMemory) 15_000 else 45_000 })
            val max = maxOf(min, when(p.mode) { BufferMode.LOW_LATENCY -> 20_000; BufferMode.BALANCED -> 90_000; BufferMode.LARGE -> 300_000; BufferMode.AUTO -> if(lowMemory) 45_000 else 120_000 })
            // Backward seek retention shares the same allocator and memory ceiling.
            // Disk retains played bytes for backwards seeks; don't duplicate them in the heap.
            val back = if(diskBuffering || lowMemory || bitrate<=0) 0 else
                minOf(p.backBufferSeconds.coerceIn(0,30)*1000L,bytes.toLong()/4*8000/bitrate).toInt().let { if(it<1000) 0 else it }
            return BufferPolicy(min, max, pre, minOf(pre, 5_000), bytes, back)
        }
    }
}
