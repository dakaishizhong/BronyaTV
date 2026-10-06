package tv.ember.client.cache

data class DiskCachePlan(val capacityBytes: Long, val aheadBytes: Long) {
    val enabled get() = capacityBytes >= 64 * MIB
    companion object {
        const val MIB = 1024L * 1024
        val sizesMb = listOf(0, 512, 1024, 2048, 4096, 8192)
        val aheadSeconds = listOf(15, 30, 60, 120, 300)
        fun create(requestedMb: Int, availableBytes: Long, existingBytes: Long, bitrate: Long, seconds: Int): DiskCachePlan {
            require((requestedMb in sizesMb || requestedMb == -1 || requestedMb == 256) && seconds in aheadSeconds)
            if (requestedMb == 0) return DiskCachePlan(0, 0)
            val requested = (if (requestedMb == -1) 1024L else maxOf(512,requestedMb).toLong()) * MIB
            // Cache files are reclaimable; keep 256 MiB free for the OS and other apps.
            val usable = (availableBytes.coerceAtLeast(0) + existingBytes.coerceAtLeast(0) - 256 * MIB).coerceAtLeast(0)
            // The setting is a forward target. Keep a small separate reserve for played data and indexes.
            val capacity = minOf(requested + 128 * MIB, usable)
            if (capacity < 64 * MIB) return DiskCachePlan(0, 0)
            return DiskCachePlan(capacity, minOf(requested, (capacity- minOf(128*MIB,capacity/8)).coerceAtLeast(0)))
        }
    }
}
