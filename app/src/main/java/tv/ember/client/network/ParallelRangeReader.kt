package tv.ember.client.network

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import okhttp3.*
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RangeUnavailableException(message: String) : IOException(message)
class RangeHttpException(val code: Int, val responseMessage: String, val headers: Map<String,List<String>>) : IOException("HTTP $code")

/** Sliding byte window, independent workers, ordered streaming reads. HTTP/1.1 is selected by the caller. */
class ParallelRangeReader(private val client: OkHttpClient, private val url: String, private val headers: Map<String,String>,
    private val start: Long, private val requestedLength: Long, val configuredConnections: Int,
    val chunkBytes: Int, val aheadWindowLimit: Int = minOf(32*1024*1024, configuredConnections*chunkBytes*2),
    private var store: RangeChunkStore? = null, private val onBytes: (Int)->Unit
) : AutoCloseable {
    private class Chunk(val position: Long, var length: Int, var bytes: ByteArray?, val background: Boolean) {
        var available = 0
        var done = false
        var consumed = false
        var persisting = false
        var allocated = bytes != null
        var requested = false
    }
    data class Snapshot(val activeRequests: Int, val downloadingChunks: Int, val completedWaitingChunks: Int,
        val orderedReadyBytes: Long, val aheadReadyBytes: Long, val aheadBufferedBytes: Long,
        val loadingDemand: Boolean, val scheduledEnd: Long, val consumedPosition: Long, val currentChunkBytes: Int)
    private val executor = Executors.newFixedThreadPool(configuredConnections) { Thread(it,"BronyaTVRange").apply { isDaemon=true } }
    // Persisting a disk-only body never occupies an HTTP worker. Exactly one body may be retained here.
    private val diskExecutor=if(store==null) null else Executors.newSingleThreadExecutor { Thread(it,"BronyaTVRangeCommit").apply { isDaemon=true } }
    private val calls = ConcurrentHashMap<Call,Boolean>() // value is the disk-only classification
    private val closed = AtomicBoolean()
    private val lock = Object()
    private val pending = ArrayDeque<Chunk>()
    private val opened = CountDownLatch(1)
    private var headersReady=false
    private var openedLength=0L
    private var workers = 0
    private var scheduledChunks = 0
    private var backgroundWorkers = 0
    private var backgroundReservedBytes=0L
    private var next = start
    private var consumedPosition = start
    private var offset = 0
    private var failure: IOException? = null
    private var diskFailed = false
    private var readWaiting = false
    @Volatile private var loading = true
    val bufferedBytes = AtomicLong() // allocated arrays, including incomplete and disk-only bodies
    val activeRequests = AtomicInteger()
    val foregroundBytes = AtomicLong()
    val backgroundBytes = AtomicLong()
    val cancelledForeground = AtomicLong()
    val cancelledBackground = AtomicLong()
    val duplicatedRangeRequests = AtomicLong()
    var responseHeaders: Map<String,List<String>> = emptyMap(); private set
    var resolvedUrl = url; private set
    @Volatile private var total = 0L
    val totalBytes: Long get() = total
    private var endExclusive = start
    private var etag: String? = null
    private var modified: String? = null
    private var validator: String? = null
    init {
        require(configuredConnections in 1..8 && chunkBytes in 1..2*1024*1024 && start>=0 && requestedLength>=-1 && requestedLength!=0L)
        require(aheadWindowLimit >= chunkBytes && aheadWindowLimit <= 32*1024*1024)
    }
    fun open(): Long {
        val probeLength = minOf(chunkBytes.toLong(),256*1024L, if(requestedLength<0) chunkBytes.toLong() else requestedLength,
            Long.MAX_VALUE-start).toInt()
        require(probeLength>0)
        // A small useful startup range gates on headers only; its body overlaps the first worker wave.
        synchronized(lock) {
            check(workers==0)
            val first = Chunk(start,probeLength,ByteArray(probeLength),false)
            pending.add(first);bufferedBytes.set(probeLength.toLong());workers++
            executor.execute { work(first,true) }
        }
        try { opened.await() } catch(e:InterruptedException) {
            Thread.currentThread().interrupt();close();throw InterruptedIOException("Range open interrupted")
        }
        return synchronized(lock) { failure?.let { close();throw it };if(closed.get()) throw InterruptedIOException("Range reader closed");openedLength }

    }
    fun setLoadingDemand(value: Boolean) = synchronized(lock) { loading=value;schedule();lock.notifyAll() }
    fun snapshot(): Snapshot = synchronized(lock) {
        var ordered=0L;var ready=0L;var contiguous=true
        pending.forEachIndexed { index,c ->
            val available=(c.available-if(index==0) offset else 0).coerceAtLeast(0)
            ready+=available
            if(contiguous) { ordered+=available;contiguous=c.available==c.length }
        }
        Snapshot(activeRequests.get(),workers,pending.count { it.done },ordered,ready,
            bufferedBytes.get(),loading,next,consumedPosition,pending.firstOrNull()?.length ?: chunkBytes)
    }
    /** Caller holds lock. Completed chunks never occupy a worker slot. Reservation precedes allocation. */
    private fun schedule() {
        if(!headersReady || closed.get() || failure!=null) return
        val horizon=consumedPosition+minOf(aheadWindowLimit.toLong(),Long.MAX_VALUE-consumedPosition)
        val diskHorizon=consumedPosition+minOf(store?.aheadBytes ?: 0,Long.MAX_VALUE-consumedPosition)
        while(workers<configuredConnections && next<endExclusive && pending.size<1024) {
            // A gradual ramp avoids a multi-MiB head-of-line gap before the playable buffer has grown.
            val wave=(scheduledChunks+1)/configuredConnections
            val requestChunk=minOf(chunkBytes,1024*1024+wave*256*1024)
            val length=minOf(requestChunk.toLong(),endExclusive-next).toInt()
            val foreground=(loading || readWaiting) && next<horizon && bufferedBytes.get()-backgroundReservedBytes+length<=aheadWindowLimit
            val background=!foreground && store?.canPrefetch==true && !diskFailed && backgroundWorkers==0 &&
                next<diskHorizon && bufferedBytes.get()+length<=aheadWindowLimit.toLong()+chunkBytes
            if(!foreground && !background) break
            val c=Chunk(next,length,ByteArray(length),background)
            scheduledChunks=(scheduledChunks+1).coerceAtMost(configuredConnections*8)
            next+=length;pending.add(c);bufferedBytes.addAndGet(length.toLong());workers++
            if(background) { backgroundWorkers++;backgroundReservedBytes+=length }
            executor.execute { work(c,false) }
        }
    }
    private fun releaseArray(c: Chunk) {
        if(c.allocated) { c.allocated=false;bufferedBytes.set((bufferedBytes.get()-c.length).coerceAtLeast(0));if(c.background) backgroundReservedBytes=(backgroundReservedBytes-c.length).coerceAtLeast(0) }
        c.bytes=null
    }
    private fun work(c: Chunk, initial: Boolean) {
        var committing=false
        try {
            val existingStore=store
            if(!initial && existingStore?.enabled==true && existingStore.contains(c.position,c.length)) {
                synchronized(lock) { releaseArray(c);c.available=c.length;c.done=true;lock.notifyAll() }
            } else {
                fetch(c,initial)
                val bytes=c.bytes
                val storage=store
                if(bytes!=null && c.available==c.length && storage?.enabled==true) {
                    if(c.background) {
                        synchronized(lock) { c.persisting=true }
                        committing=true
                        requireNotNull(diskExecutor).execute {
                            val saved=runCatching { storage.persist(c.position,bytes) }.getOrDefault(false)
                            synchronized(lock) {
                                c.persisting=false
                                if(saved || c.consumed) releaseArray(c)
                                if(!saved) diskFailed=true
                                backgroundWorkers--;schedule();lock.notifyAll()
                            }
                        }
                    } else storage.offer(c.position,bytes)
                }
            }
        } catch(e:Exception) {
            val io=e as? IOException ?: IOException(Tr.text(UiText.RANGE_REQUEST_FAILED_117),e)
            synchronized(lock) {
                if(!closed.get() && failure==null) { failure=io;opened.countDown();cancelCalls() }
            }
        } finally {
            synchronized(lock) {
                c.done=true
                if(c.consumed && !c.persisting) releaseArray(c)
                workers--;if(c.background && !committing) backgroundWorkers--
                schedule();lock.notifyAll()
            }
        }
    }
    private fun fetch(c: Chunk, initial: Boolean) {
        if(closed.get()) throw InterruptedIOException("Range reader closed")
        val limit=c.position+c.length
        val request=Request.Builder().url(url)
        headers.forEach { (k,v)->request.header(k,v) }
        request.header("Accept-Encoding","identity").header("Range","bytes=${c.position}-${limit-1}")
        if(!initial && validator!=null) request.header("If-Range",validator!!)
        if(c.requested) duplicatedRangeRequests.incrementAndGet()
        c.requested=true
        val call=client.newCall(request.build());calls[call]=c.background
        activeRequests.incrementAndGet()
        try {
            if(closed.get()) { call.cancel();throw InterruptedIOException("Range reader closed") }
            call.execute().use { response ->
                if(initial && response.code==416) {
                    val size=Regex("bytes \\*/(\\d+)",RegexOption.IGNORE_CASE).matchEntire(response.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                    if(size!=null && c.position==size) {
                        synchronized(lock) {
                            total=size;endExclusive=size;next=size;responseHeaders=response.headers.toMultimap();resolvedUrl=response.request.url.toString()
                            pending.clear();releaseArray(c);c.done=true;openedLength=0;headersReady=true;opened.countDown();lock.notifyAll()
                        }
                        return
                    }
                }
                if(response.code!=206) {
                    if(initial && response.code==200) throw RangeUnavailableException(Tr.text(UiText.SERVER_DOES_NOT_SUPPORT_PARALLEL_RANGE_106))
                    if(response.code==200) throw IOException(Tr.text(UiText.SOURCE_OR_RANGE_RESPONSE_CHANGED_REFRESH_107))
                    throw RangeHttpException(response.code,response.message,response.headers.toMultimap())
                }
                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)",RegexOption.IGNORE_CASE).matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw if(initial) RangeUnavailableException(Tr.text(UiText.SERVER_HAS_NO_VALID_CONTENT_RANGE_108)) else IOException(Tr.text(UiText.INVALID_CONTENT_RANGE_109))
                val from=match.groupValues[1].toLongOrNull();val to=match.groupValues[2].toLongOrNull();val size=match.groupValues[3].toLongOrNull()
                if(from!=c.position || to==null || size==null || size<=to || to<from || to!=minOf(limit,size)-1) {
                    throw if(initial) RangeUnavailableException(Tr.text(UiText.SERVER_RETURNED_A_DIFFERENT_RANGE_POSITION_110)) else IOException(Tr.text(UiText.RANGE_POSITION_MISMATCH_MERGING_STOPPED_111))
                }
                if(response.header("Content-Encoding").orEmpty().let { it.isNotEmpty() && !it.equals("identity",true) })
                    throw RangeUnavailableException(Tr.text(UiText.SERVER_COMPRESSED_THE_RANGE_RESPONSE_USING_112))
                val actualLength=(to-from+1).toInt()
                val body=response.body ?: throw IOException(Tr.text(UiText.EMPTY_RANGE_RESPONSE_114))
                if(body.contentLength()>=0 && body.contentLength()!=actualLength.toLong()) throw IOException(Tr.text(UiText.INCONSISTENT_RANGE_RESPONSE_LENGTH_115))
                if(initial) {
                    total=size;etag=response.header("ETag");modified=response.header("Last-Modified")
                    validator=etag?.takeUnless { it.startsWith("W/",true) } ?: modified
                    // Do not parallelize a redirected manifest with an extensionless origin URL.
                    if(StreamPolicy.isPlaylist(response.request.url.toString(),response.header("Content-Type").orEmpty())) throw RangeUnavailableException("Playlist uses Media3 segment loading")
                    store=store?.validate(RangeIdentity(size,etag,modified))
                    synchronized(lock) {
                        if(closed.get()) throw InterruptedIOException("Range reader closed")
                        responseHeaders=response.headers.toMultimap();resolvedUrl=response.request.url.toString()
                        endExclusive=if(requestedLength<0) size else start+minOf(requestedLength,size-start)
                        // The tail can be shorter than the requested first chunk.
                        if(actualLength<c.length) {
                            releaseArray(c);c.length=actualLength;c.bytes=ByteArray(actualLength);c.allocated=true
                            bufferedBytes.addAndGet(actualLength.toLong())
                        }
                        next=start+c.length
                        openedLength=endExclusive-start;headersReady=true;opened.countDown();schedule();lock.notifyAll()
                    }
                } else if(size!=total || response.header("ETag")!=etag || response.header("Last-Modified")!=modified) {
                    throw IOException(Tr.text(UiText.SOURCE_SIZE_OR_VERSION_CHANGED_MERGING_113))
                }
                readBody(body,c,actualLength)
            }
        } finally { calls.remove(call);activeRequests.decrementAndGet() }
    }
    private fun readBody(body: ResponseBody,c: Chunk,length: Int) {
        val bytes=requireNotNull(c.bytes)
        body.byteStream().use { input ->
            var cursor=0
            while(cursor<length) {
                if(closed.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Range reader closed")
                val n=input.read(bytes,cursor,minOf(65536,length-cursor))
                if(n<0) throw IOException(Tr.text(UiText.RANGE_RESPONSE_ENDED_EARLY_116))
                if(n==0) continue
                cursor+=n
                (if(c.background) backgroundBytes else foregroundBytes).addAndGet(n.toLong());onBytes(n)
                synchronized(lock) { c.available=cursor;lock.notifyAll() }
            }
        }
    }
    fun read(buffer: ByteArray,destination: Int,length: Int): Int {
        require(destination>=0 && length>=0 && destination<=buffer.size-length)
        if(length==0) return 0
        synchronized(lock) {
            while(true) {
                if(closed.get()) throw InterruptedIOException(Tr.text(UiText.PARALLEL_RECEIVE_STOPPED_105))
                failure?.let { throw it }
                var c=pending.firstOrNull()
                if(c==null) {
                    if(next>=endExclusive) return -1
                    readWaiting=true;schedule();readWaiting=false
                    c=pending.firstOrNull()
                    if(c==null) { lock.wait(10);continue }
                }
                if(offset<c.available) {
                    val n=minOf(length,c.available-offset)
                    val bytes=c.bytes
                    if(bytes!=null) bytes.copyInto(buffer,destination,offset,offset+n)
                    else {
                        val count=store?.read(c.position+offset,buffer,destination,n) ?: -1
                        if(count<0) {
                            // A cache eviction is a legitimate retry, never a second concurrent downloader.
                            if(!c.done || workers>=configuredConnections || bufferedBytes.get()+c.length>aheadWindowLimit.toLong()+2*chunkBytes) {
                                try { lock.wait(10) } catch(e:InterruptedException) { Thread.currentThread().interrupt();throw InterruptedIOException("Range read interrupted") }
                                continue
                            }
                            c.bytes=ByteArray(c.length);c.allocated=true;bufferedBytes.addAndGet(c.length.toLong())
                            c.available=0;c.done=false;workers++;if(c.background) { backgroundWorkers++;backgroundReservedBytes+=c.length }
                            executor.execute { work(c,false) };continue
                        }
                        offset+=count;consumedPosition+=count;return count
                    }
                    offset+=n;consumedPosition+=n;return n
                }
                if(offset==c.length) {
                    pending.removeFirst();c.consumed=true
                    // A disk write may retain its one reserved body until it finishes.
                    if(c.done && !c.persisting) releaseArray(c)
                    offset=0;schedule();continue
                }
                readWaiting=true;schedule()
                try { lock.wait() } catch(e:InterruptedException) {
                    Thread.currentThread().interrupt();throw InterruptedIOException(Tr.text(UiText.PARALLEL_RECEIVE_INTERRUPTED_118))
                } finally { readWaiting=false }
            }
        }
    }
    private fun cancelCalls() {
        calls.forEach { (call,background) ->
            if(!call.isCanceled()) { (if(background) cancelledBackground else cancelledForeground).incrementAndGet();call.cancel() }
        }
    }
    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        cancelCalls()
        synchronized(lock) {
            opened.countDown()
            pending.clear();executor.shutdownNow();diskExecutor?.shutdownNow();bufferedBytes.set(0);backgroundReservedBytes=0;lock.notifyAll()
        }
    }
}
