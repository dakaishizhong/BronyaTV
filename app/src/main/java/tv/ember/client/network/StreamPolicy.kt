package tv.ember.client.network

data class StreamPlan(val connections: Int, val budgetBytes: Int, val chunkBytes: Int = 1024*1024, val aheadWindowBytes: Int = budgetBytes)

object StreamPolicy {
    fun isPlaylist(url:String,contentType:String=""):Boolean =
        url.substringBefore('?').let { it.endsWith(".m3u8",true) || it.endsWith(".mpd",true) } ||
            contentType.lowercase().let { "mpegurl" in it || "dash+xml" in it }
    fun create(requested:Int,bitrate:Long,heapMax:Long,usedHeap:Long,lowMemory:Boolean,diskBuffering:Boolean=false):StreamPlan {
        require(requested in listOf(0,1,2,4,8))
        val mib=1024*1024
        val available=(heapMax-usedHeap).coerceAtLeast(0)
        val cap=minOf((if(lowMemory) 4L else 32L)*mib,heapMax/8,available/8).toInt()
        val desired=if(requested>0) requested else when {
            bitrate>=48_000_000 -> 8
            bitrate>=12_000_000 -> 4
            bitrate>=6_000_000 || bitrate<=0 -> 2
            else -> 1
        }
        val storageBodies=if(diskBuffering) 4 else 0
        val count=if(lowMemory || cap<65536*(2*desired+storageBodies)) 1 else desired
        // Keep two full worker waves inside the window, after reserving storage bodies.
        // Large heaps use two MiB ranges; constrained devices scale down without collapsing to one wave.
        val chunk=minOf(if(bitrate>=48_000_000) 2*mib else mib,
            cap/(2*count+storageBodies)/65536*65536).coerceAtLeast(65536)
        val target=maxOf(2L*count*chunk,bitrate.coerceAtLeast(0)/8*3).coerceAtMost(32L*mib)
        val budget=minOf(cap.toLong(),target+(if(diskBuffering) 4L*chunk else 0)).toInt()
        // One disk-only body, one eviction retry and two asynchronous write copies are included in this same cap.
        val window=(budget-(if(diskBuffering) 4*chunk else 0)).coerceAtLeast(0)
        return StreamPlan(count,budget,chunk,window)
    }
}
