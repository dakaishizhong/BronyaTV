package tv.ember.client.cache

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/** Size-adjustable LRU. All accesses run under SimpleCache's monitor. */
@UnstableApi
class PlaybackCacheEvictor(private var maxBytes: Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan> { a, b ->
        val time = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
        if (time == 0) a.compareTo(b) else time
    }
    private var size = 0L
    private var playbackKey:String?=null
    private var playbackPosition=0L
    fun setPlaybackWindow(cache:Cache,key:String,position:Long)=synchronized(cache) {
        playbackKey=key;playbackPosition=position.coerceAtLeast(0)
    }
    fun resize(cache: Cache, bytes: Long) = synchronized(cache) {
        require(bytes >= 0); maxBytes = bytes; evict(cache, 0)
    }
    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() {}
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        if (spans.add(span)) size += span.length
        evict(cache, 0)
    }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) size -= span.length
    }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan)
    }
    private fun evict(cache: Cache, incoming: Long) {
        while (size + incoming > maxBytes && spans.isNotEmpty()) {
            // Played data and other sources yield before the current forward window, even if
            // a recently read, played span has a newer LRU timestamp than prefetched data.
            val victim=if(playbackKey==null) spans.first() else spans.firstOrNull {
                it.key!=playbackKey || it.position+it.length<=playbackPosition
            } ?: spans.first()
            cache.removeSpan(victim)
        }
    }
}
