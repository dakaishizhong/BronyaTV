package tv.ember.client.cache

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
    @Volatile var state = "等待播放位置"; private set
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
                    state = "磁盘空间不足，停止提前写入"
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
                if (window == null) { state = "前向缓存已就绪"; waitForChange(500); continue }
                val spec = DataSpec.Builder().setUri(url).setKey(key).setPosition(window.position).setLength(window.length)
                    .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
                val next = CacheWriter(factory.createDataSource(), spec, buffer, null)
                synchronized(lock) {
                    if (closed || epoch != generation) next.cancel()
                    writer = next
                }
                state = "正在提前缓存"
                try { next.cache() } finally { synchronized(lock) { if (writer === next) writer = null } }
                if (range.rangeUnsupported) {
                    state = "服务器不支持 Range，使用播放网络缓冲"
                    synchronized(lock) { stopped = true }
                }
                failures = 0
            } catch (e: InterruptedException) {
                if (closed) return
            } catch (e: Exception) {
                if (closed) return
                state = "预取暂缓，继续读取缓存或网络"
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
