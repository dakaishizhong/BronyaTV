package tv.ember.client.network

/** The identity is obtained from a fresh, strictly checked 206 before any cached bytes are exposed. */
data class RangeIdentity(val total: Long, val etag: String?, val lastModified: String?, val resourceUrl: String? = null)

/** One scheduler owns every offset. Storage never starts its own network requests. */
interface RangeChunkStore {
    val identity: RangeIdentity? get() = null
    val aheadBytes: Long
    val enabled: Boolean
    /** A failed/low-space writer may still have valid, readable cached spans. */
    val canPrefetch: Boolean get() = enabled
    fun validate(identity: RangeIdentity): RangeChunkStore
    fun contains(position: Long, length: Int): Boolean
    /** Positive contiguous cached length, or negative hole length up to the next span. */
    fun cachedLength(position: Long, length: Long): Long {
        val count=minOf(length,Int.MAX_VALUE.toLong()).toInt()
        return if(contains(position,count)) count.toLong() else -count.toLong()
    }
    /** Returns -1 if an evicted span is no longer available. */
    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int
    /** Foreground offers must return immediately and use a bounded copy queue. */
    fun offer(position: Long, bytes: ByteArray)
    /** Only the single disk worker calls this; false disables further disk-only scheduling. */
    fun persist(position: Long, bytes: ByteArray): Boolean
}
