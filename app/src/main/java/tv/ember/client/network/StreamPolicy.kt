package tv.ember.client.network

data class StreamPlan(val connections: Int, val budgetBytes: Int)

object StreamPolicy {
    fun create(requested: Int, bitrate: Long, heapMax: Long, usedHeap: Long, lowMemory: Boolean): StreamPlan {
        require(requested in listOf(0,1,2,4,8))
        val budget=minOf(16L*1024*1024,heapMax/8,(heapMax-usedHeap).coerceAtLeast(0)/8).toInt()
        val count=if(requested>0) requested else when {
            bitrate>=12_000_000 && budget>=5*1024*1024 -> 4
            (bitrate>=6_000_000 || bitrate<=0) && budget>=3*1024*1024 -> 2
            else -> 1
        }
        return StreamPlan(if(lowMemory || budget<65536*(count+1)) 1 else count,budget)
    }
}
