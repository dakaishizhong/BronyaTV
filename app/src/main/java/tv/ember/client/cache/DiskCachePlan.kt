package tv.ember.client.cache

data class DiskCachePlan(val capacityBytes: Long, val aheadBytes: Long) {
    val enabled get() = capacityBytes >= 64 * MIB
    companion object {
        const val MIB = 1024L * 1024
        val sizesMb = listOf(0, -1, 256, 512, 1024, 2048, 4096, 8192)
        val aheadSeconds = listOf(15, 30, 60, 120, 300)
        fun create(requestedMb: Int, availableBytes: Long, existingBytes: Long, bitrate: Long, seconds: Int): DiskCachePlan {
            require(requestedMb in sizesMb && seconds in aheadSeconds)
            if (requestedMb == 0) return DiskCachePlan(0, 0)
            val requested = (if (requestedMb == -1) 512L else requestedMb.toLong()) * MIB
            // Cache files are reclaimable; keep 256 MiB free for the OS and other apps.
            val usable = (availableBytes.coerceAtLeast(0) + existingBytes.coerceAtLeast(0) - 256 * MIB).coerceAtLeast(0)
            val capacity = minOf(requested, usable)
            if (capacity < 64 * MIB) return DiskCachePlan(0, 0)
            val desired = if (bitrate > 0) (bitrate / 8).coerceAtMost(Long.MAX_VALUE / seconds) * seconds else 64 * MIB
            // Leave space for backward seeks and container indexes instead of evicting every played byte.
            return DiskCachePlan(capacity, minOf(maxOf(desired, 8 * MIB), capacity * 3 / 4))
        }
    }
}
