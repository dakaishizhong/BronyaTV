package tv.ember.client.network

data class StreamPlan(val connections: Int, val budgetBytes: Int)

object StreamPolicy {
    fun create(requested: Int, bitrate: Long, heapMax: Long, usedHeap: Long, lowMemory: Boolean, diskBuffering: Boolean = false): StreamPlan {
        require(requested in listOf(0,1,2,4,8))
        // Disk prefetch and foreground fallback can overlap. Each gets half the old RAM budget.
        val budget=minOf((if(diskBuffering) 8L else 16L)*1024*1024,heapMax/(if(diskBuffering) 16 else 8),
            (heapMax-usedHeap).coerceAtLeast(0)/(if(diskBuffering) 16 else 8)).toInt()
        val count=if(requested>0) requested else when {
            diskBuffering && bitrate>=48_000_000 && budget>=1024*1024 -> 8
            bitrate>=12_000_000 && budget>=5*1024*1024 -> 4
            (bitrate>=6_000_000 || bitrate<=0) && budget>=3*1024*1024 -> 2
            else -> 1
        }
        return StreamPlan(if(lowMemory || budget<65536*(count+1)) 1 else count,budget)
    }
}
