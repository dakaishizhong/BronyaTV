package tv.ember.client.cache

data class PrefetchWindow(val position: Long, val length: Long) {
    companion object {
        fun next(position: Long, aheadBytes: Long, totalBytes: Long, contiguousBytes: Long): PrefetchWindow? {
            require(position >= 0 && aheadBytes >= 0 && contiguousBytes >= 0)
            val horizon = position + minOf(aheadBytes, Long.MAX_VALUE - position)
            val end = if (totalBytes >= 0) minOf(totalBytes, horizon) else horizon
            if (position >= end) return null
            val ready = minOf(contiguousBytes, end - position)
            val start = position + ready
            val length = minOf(8 * DiskCachePlan.MIB, end - start)
            return if (length > 0) PrefetchWindow(start, length) else null
        }
    }
}
