package tv.ember.client.network

/** The identity is obtained from a fresh, strictly checked 206 before any cached bytes are exposed. */
data class RangeIdentity(val total: Long, val etag: String?, val lastModified: String?)

/** One scheduler owns every offset. Storage never starts its own network requests. */
interface RangeChunkStore {
    val aheadBytes: Long
    val enabled: Boolean
    /** A failed/low-space writer may still have valid, readable cached spans. */
    val canPrefetch: Boolean get() = enabled
    fun validate(identity: RangeIdentity): RangeChunkStore
    fun contains(position: Long, length: Int): Boolean
    /** Returns -1 if an evicted span is no longer available. */
    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int
    /** Foreground offers must return immediately and use a bounded copy queue. */
    fun offer(position: Long, bytes: ByteArray)
    /** Only the single disk worker calls this; false disables further disk-only scheduling. */
    fun persist(position: Long, bytes: ByteArray): Boolean
}
