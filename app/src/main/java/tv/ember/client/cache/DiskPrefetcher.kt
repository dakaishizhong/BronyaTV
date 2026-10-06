package tv.ember.client.cache

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import okhttp3.OkHttpClient
import tv.ember.client.network.RangePlaybackStatus
import java.util.concurrent.atomic.AtomicLong

/** Downloads ahead of the extractor independently of Media3's RAM allocator. */
@UnstableApi
class DiskPrefetcher(
    val handle: PlaybackDiskCache.Handle, private val factory: CacheDataSource.Factory,
    private val client: OkHttpClient, private val url: String, val key: String,
    private val range: RangePlaybackStatus
) : AutoCloseable {
    private val lock = Object()
    @Volatile private var closed = false
    @Volatile private var writer: CacheWriter? = null
    private var cursor = 0L
    private var generation = 0L
    private var positioned = false
    private var stopped = false
    val hitBytes = AtomicLong()
    val rangeStatus get() = range
    @Volatile var state = Tr.text(UiText.WAITING_FOR_PLAYBACK_POSITION_001); private set
    val capacityBytes get() = handle.plan.capacityBytes
    val aheadBytes: Long get() = synchronized(lock) {
        if (!positioned || closed) 0 else handle.cache.getCachedLength(key, cursor, handle.plan.aheadBytes).coerceAtLeast(0)
    }
    val usedBytes get() = handle.cache.cacheSpace
    private val worker = Thread(::run, "BronyaTVDiskPrefetch").apply { isDaemon = true; start() }

    fun seek(position: Long) = synchronized(lock) {
        if (closed) return@synchronized
        cursor = position; positioned = true; generation++
        writer?.cancel(); client.dispatcher.cancelAll(); lock.notifyAll()
    }
    fun advance(position: Long) = synchronized(lock) { cursor = position; lock.notifyAll() }
    private fun waitForChange(ms: Long) = synchronized(lock) { if (!closed) lock.wait(ms) }
    private fun run() {
        val buffer = ByteArray(128 * 1024)
        var retryAt = 0L
        var retryGeneration = -1L
        var failures = 0
        while (!closed) {
            try {
                val request = synchronized(lock) { if (!positioned || stopped) null else cursor to generation }
                if (request == null) { waitForChange(500); continue }
                if (handle.directory.usableSpace < 64 * DiskCachePlan.MIB) {
                    state = Tr.text(UiText.LOW_DISK_SPACE_READ_AHEAD_STOPPED_002)
                    synchronized(lock) { stopped = true }
                    continue
                }
                val (position, epoch) = request
                if (epoch != retryGeneration) { retryAt = 0; failures = 0; retryGeneration = epoch }
                if (SystemClock.elapsedRealtime() < retryAt) { waitForChange(500); continue }
                val metadataLength = ContentMetadata.getContentLength(handle.cache.getContentMetadata(key))
                val knownLength = range.totalBytes.takeIf { it > 0 } ?: metadataLength
                val ready = handle.cache.getCachedLength(key, position, handle.plan.aheadBytes).coerceAtLeast(0)
                val window = PrefetchWindow.next(position, handle.plan.aheadBytes, knownLength, ready)
                if (window == null) { state = Tr.text(UiText.READ_AHEAD_CACHE_READY_003); waitForChange(500); continue }
                val spec = DataSpec.Builder().setUri(url).setKey(key).setPosition(window.position).setLength(window.length)
                    .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
                val next = CacheWriter(factory.createDataSource(), spec, buffer, null)
                synchronized(lock) {
                    if (closed || epoch != generation) next.cancel()
                    writer = next
                }
                state = Tr.text(UiText.CACHING_AHEAD_004)
                try { next.cache() } finally { synchronized(lock) { if (writer === next) writer = null } }
                if (range.rangeUnsupported) {
                    state = Tr.text(UiText.SERVER_DOES_NOT_SUPPORT_RANGE_USING_005)
                    synchronized(lock) { stopped = true }
                }
                failures = 0
            } catch (e: InterruptedException) {
                if (closed) return
            } catch (e: Exception) {
                if (closed) return
                state = Tr.text(UiText.READ_AHEAD_PAUSED_READING_CACHE_OR_006)
                failures = (failures + 1).coerceAtMost(5)
                retryAt = SystemClock.elapsedRealtime() + (1000L shl failures).coerceAtMost(30_000)
            }
        }
    }
    override fun close() {
        synchronized(lock) { closed = true; writer?.cancel(); client.dispatcher.cancelAll(); lock.notifyAll() }
        worker.interrupt()
        if (Thread.currentThread() !== worker) runCatching { worker.join(1000) }
    }
}
