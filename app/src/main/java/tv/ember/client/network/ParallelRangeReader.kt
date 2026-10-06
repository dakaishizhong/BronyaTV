package tv.ember.client.network

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import okhttp3.*
import java.io.IOException
import java.io.InterruptedIOException
import java.util.TreeMap
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RangeUnavailableException(message: String) : IOException(message)
class RangeHttpException(val code: Int, val responseMessage: String, val headers: Map<String,List<String>>) : IOException("HTTP $code")

/** One range owner for urgent reads and a continuously replenished disk window. */
class ParallelRangeReader(private val client: OkHttpClient, private val url: String, private val headers: Map<String,String>,
    private val start: Long, private val requestedLength: Long, val configuredConnections: Int,
    val chunkBytes: Int, val aheadWindowLimit: Int = minOf(32*1024*1024, configuredConnections*chunkBytes*2),
    private var store: RangeChunkStore? = null, private val onBytes: (Int)->Unit
) : AutoCloseable {
    private class Chunk(val position: Long, var length: Int, var bytes: ByteArray?, val background: Boolean) {
        var available=0;var done=false;var persisting=false;var allocated=bytes!=null;var requested=false
        var retries=0;var running=true
        val end get()=position+length
    }
    data class Snapshot(val activeRequests: Int, val downloadingChunks: Int, val completedWaitingChunks: Int,
        val orderedReadyBytes: Long, val aheadReadyBytes: Long, val aheadBufferedBytes: Long,
        val loadingDemand: Boolean, val scheduledEnd: Long, val consumedPosition: Long, val currentChunkBytes: Int,
        val foregroundConnections: Int, val prefetchConnections: Int, val diskHighWatermark: Long,
        val playbackPosition: Long)
    private val executor=Executors.newScheduledThreadPool(configuredConnections) { Thread(it,"BronyaTVRange").apply { isDaemon=true } }
    private val diskExecutor=if(store==null) null else Executors.newSingleThreadExecutor { Thread(it,"BronyaTVRangeCommit").apply { isDaemon=true } }
    private val calls=ConcurrentHashMap<Call,Boolean>()
    private val closed=AtomicBoolean()
    private val lock=Object()
    // Reservations remain here through network reads, retries and disk commits.
    private val chunks=TreeMap<Long,Chunk>()
    private val opened=CountDownLatch(1)
    private var headersReady=false
    private var openedLength=0L
    private var workers=0
    private var next=start
    private var scheduledChunks=0
    private var consumedPosition=start
    private var playbackPosition=start
    private var explicitPlaybackPosition=false
    private var diskHighWatermark=start
    private var failure:IOException?=null
    private var diskFailed=false
    private var readWaiting=false
    @Volatile private var loading=true
    val bufferedBytes=AtomicLong()
    val activeRequests=AtomicInteger()
    val foregroundBytes=AtomicLong()
    val backgroundBytes=AtomicLong()
    val cancelledForeground=AtomicLong()
    val cancelledBackground=AtomicLong()
    val duplicatedRangeRequests=AtomicLong()
    var responseHeaders:Map<String,List<String>> = emptyMap();private set
    var resolvedUrl=url;private set
    @Volatile private var total=0L
    val totalBytes:Long get()=total
    private var endExclusive=start
    private var etag:String?=null
    private var modified:String?=null
    private var validator:String?=null
    init {
        require(configuredConnections in 1..8 && chunkBytes in 1..8*1024*1024 && start>=0 && requestedLength>=-1 && requestedLength!=0L)
        require(aheadWindowLimit>=chunkBytes && aheadWindowLimit<=32*1024*1024)
    }
    fun open():Long {
        synchronized(lock) {
            val identity=store?.identity?.takeIf { it.resourceUrl==url && (it.etag?.startsWith("W/",true)==false || it.lastModified!=null) }
            if(identity!=null && start<=identity.total) {
                store=store?.validate(identity)
                total=identity.total;etag=identity.etag;modified=identity.lastModified
                validator=etag?.takeUnless { it.startsWith("W/",true) } ?: modified
                endExclusive=if(requestedLength<0) total else start+minOf(requestedLength,total-start)
                openedLength=endExclusive-start;headersReady=true
                responseHeaders=mapOf("Content-Range" to listOf("bytes $start-${(endExclusive-1).coerceAtLeast(start)}/$total"))
                opened.countDown();schedule()
            } else {
                val length=minOf(chunkBytes.toLong(),256*1024L,if(requestedLength<0) chunkBytes.toLong() else requestedLength,Long.MAX_VALUE-start).toInt()
                require(length>0)
                reserve(start,length,false,true)
            }
        }
        try { opened.await() } catch(e:InterruptedException) {
            Thread.currentThread().interrupt();close();throw InterruptedIOException("Range open interrupted")
        }
        return synchronized(lock) { failure?.let { close();throw it };if(closed.get()) throw InterruptedIOException("Range reader closed");openedLength }
    }
    fun setLoadingDemand(value:Boolean)=synchronized(lock) { loading=value;schedule();lock.notifyAll() }
    fun setPlaybackPosition(position:Long)=synchronized(lock) {
        explicitPlaybackPosition=true;playbackPosition=position.coerceIn(0,total.coerceAtLeast(0));schedule();lock.notifyAll()
    }
    private fun chunkAt(position:Long)=chunks.floorEntry(position)?.value?.takeIf { position<it.end }
    private fun orderedReady():Long {
        var position=consumedPosition
        while(position<endExclusive) {
            val cached=cachedLength(position,endExclusive-position)
            if(cached>0) { position+=cached;continue }
            val c=chunkAt(position) ?: break
            val ready=c.position+c.available
            if(ready<=position) break
            position=ready
            if(c.available<c.length) break
        }
        return position-consumedPosition
    }
    fun snapshot():Snapshot=synchronized(lock) {
        Snapshot(activeRequests.get(),workers,chunks.values.count { it.done },orderedReady(),
            chunks.values.sumOf { (it.position+it.available-maxOf(it.position,consumedPosition)).coerceAtLeast(0) },
            bufferedBytes.get(),loading,next,consumedPosition,chunkAt(consumedPosition)?.length ?: chunkBytes,
            calls.values.count { !it },calls.values.count { it },diskHighWatermark,playbackPosition)
    }
    private fun cachedLength(position:Long,length:Long):Long = if(store?.enabled==true) store!!.cachedLength(position,length) else -length
    /** Find a hole by skipping committed spans and every reserved (including partially received) range. */
    private fun missing(from:Long,horizon:Long):Long {
        var position=from
        while(position<horizon) {
            val c=chunkAt(position)
            if(c!=null) { position=c.end;continue }
            val cached=cachedLength(position,horizon-position)
            if(cached>0) { position+=cached;continue }
            break
        }
        return position
    }
    private fun lengthAt(position:Long,horizon:Long):Int {
        // Grow the first worker waves gradually so a large steady-state chunk does not
        // create a head-of-line gap before the playable RAM buffer has been established.
        val requestChunk=minOf(chunkBytes,1024*1024+scheduledChunks/configuredConnections*256*1024)
        val end=minOf(horizon,position+minOf(requestChunk.toLong(),Long.MAX_VALUE-position),chunks.ceilingKey(position) ?: Long.MAX_VALUE)
        val cached=cachedLength(position,end-position)
        return minOf(end-position,if(cached<0) -cached else 0).toInt()
    }
    private fun reserve(position:Long,length:Int,background:Boolean,initial:Boolean=false) {
        check(length>0 && chunkAt(position)==null && (chunks.ceilingKey(position) ?: Long.MAX_VALUE)>=position+length)
        val c=Chunk(position,length,ByteArray(length),background)
        scheduledChunks=(scheduledChunks+1).coerceAtMost(configuredConnections*32)
        chunks[position]=c;bufferedBytes.addAndGet(length.toLong());workers++;next=maxOf(next,c.end)
        executor.execute { work(c,initial) }
    }
    /** Completed HTTP bodies release their lane immediately; a bounded RAM reservation covers disk IO. */
    private fun schedule() {
        if(!headersReady || closed.get() || failure!=null) return
        val ramHorizon=minOf(endExclusive,consumedPosition+minOf(aheadWindowLimit.toLong(),Long.MAX_VALUE-consumedPosition))
        val diskHorizon=minOf(endExclusive,playbackPosition+minOf(store?.aheadBytes ?: 0,Long.MAX_VALUE-playbackPosition))
        val diskEnabled=store?.canPrefetch==true && !diskFailed
        val foregroundTarget=if(!diskEnabled) configuredConnections else if(loading && orderedReady()<2L*chunkBytes || readWaiting)
            (configuredConnections-1).coerceAtLeast(1) else minOf(2,configuredConnections)
        while(workers<configuredConnections) {
            val foregroundPosition=missing(consumedPosition,ramHorizon)
            val foregroundCount=chunks.values.count { !it.background && it.running }
            val foreground= (loading || readWaiting) && foregroundPosition<ramHorizon && foregroundCount<foregroundTarget
            val position=if(foreground) foregroundPosition else if(diskEnabled) missing(maxOf(start,playbackPosition),diskHorizon) else break
            val horizon=if(foreground) ramHorizon else diskHorizon
            if(position>=horizon) break
            val length=lengthAt(position,horizon)
            if(length<=0) break
            // Keep a playback body available even when all disk commits are slow.
            val limit=if(foreground) aheadWindowLimit.toLong() else (aheadWindowLimit- minOf(2*chunkBytes,aheadWindowLimit/2)).toLong()
            if(bufferedBytes.get()+length>limit) break
            reserve(position,length,!foreground)
        }
        diskHighWatermark=if(diskEnabled) missing(maxOf(start,playbackPosition),diskHorizon) else playbackPosition
    }
    private fun removeChunk(c:Chunk) { if(chunks[c.position]===c) chunks.remove(c.position) }
    private fun releaseArray(c:Chunk) {
        if(c.allocated) { c.allocated=false;bufferedBytes.set((bufferedBytes.get()-c.length).coerceAtLeast(0)) }
        c.bytes=null
    }
    private fun work(c:Chunk,initial:Boolean) {
        var retry=false
        try {
            fetch(c,initial)
            synchronized(lock) {
                c.done=true
                val bytes=c.bytes;val storage=store
                if(bytes!=null && storage?.canPrefetch==true && !diskFailed) {
                    c.persisting=true
                    requireNotNull(diskExecutor).execute {
                        val saved= !closed.get() && !diskFailed && runCatching { storage.persist(c.position,bytes) }.getOrDefault(false)
                        synchronized(lock) {
                            c.persisting=false
                            if(saved || consumedPosition>=c.end) { releaseArray(c);removeChunk(c) }
                            if(!saved && !closed.get()) diskFailed=true
                            schedule();lock.notifyAll()
                        }
                    }
                } else if(consumedPosition>=c.end) { releaseArray(c);removeChunk(c) }
            }
        } catch(e:Exception) {
            val io=e as? IOException ?: IOException(Tr.text(UiText.RANGE_REQUEST_FAILED_117),e)
            synchronized(lock) {
                val transient=io !is RangeUnavailableException && (io !is RangeHttpException || io.code in listOf(408,429,500,502,503,504)) &&
                    !io.message.orEmpty().contains("changed") && !io.message.orEmpty().contains("mismatch") && !io.message.orEmpty().contains("Content-Range")
                if(headersReady && !closed.get() && transient && c.retries<4) {
                    c.retries++;retry=true
                    executor.schedule({ work(c,false) },minOf(2000L,250L shl (c.retries-1)),TimeUnit.MILLISECONDS)
                } else if(!closed.get() && failure==null) { failure=io;opened.countDown();cancelCalls() }
            }
        } finally {
            synchronized(lock) {
                if(!retry) { c.running=false;workers--;if(closed.get()) releaseArray(c);schedule() }
                lock.notifyAll()
            }
        }
    }
    private fun fetch(c: Chunk, initial: Boolean) {
        if(closed.get()) throw InterruptedIOException("Range reader closed")
        val fromPosition=c.position+c.available
        val limit=c.position+c.length
        val request=Request.Builder().url(url)
        headers.forEach { (k,v)->request.header(k,v) }
        request.header("Accept-Encoding","identity").header("Range","bytes=${fromPosition}-${limit-1}")
        if(!initial && validator!=null) request.header("If-Range",validator!!)
        if(c.requested && c.available==c.length) duplicatedRangeRequests.incrementAndGet()
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
                            chunks.clear();releaseArray(c);c.done=true;openedLength=0;headersReady=true;opened.countDown();lock.notifyAll()
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
                if(from!=fromPosition || to==null || size==null || size<=to || to<from || to!=minOf(limit,size)-1) {
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
                    store=store?.validate(RangeIdentity(size,etag,modified,url))
                    synchronized(lock) {
                        if(closed.get()) throw InterruptedIOException("Range reader closed")
                        responseHeaders=response.headers.toMultimap();resolvedUrl=response.request.url.toString()
                        endExclusive=if(requestedLength<0) size else start+minOf(requestedLength,size-start)
                        // The tail can be shorter than the requested first chunk.
                        if(actualLength<c.length) {
                            releaseArray(c);c.length=actualLength;c.bytes=ByteArray(actualLength);c.allocated=true
                            bufferedBytes.addAndGet(actualLength.toLong())
                        }
                        next=start+c.length;diskHighWatermark=start
                        openedLength=endExclusive-start;headersReady=true;opened.countDown();schedule();lock.notifyAll()
                    }
                } else if(size!=total || response.header("ETag")!=etag || response.header("Last-Modified")!=modified) {
                    throw IOException(Tr.text(UiText.SOURCE_SIZE_OR_VERSION_CHANGED_MERGING_113))
                }
                readBody(body,c,c.available+actualLength)
            }
        } finally { calls.remove(call);activeRequests.decrementAndGet() }
    }
    private fun readBody(body: ResponseBody,c: Chunk,length: Int) {
        val bytes=requireNotNull(c.bytes)
        body.byteStream().use { input ->
            var cursor=c.available
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
    fun read(buffer:ByteArray,destination:Int,length:Int):Int {
        require(destination>=0 && length>=0 && destination<=buffer.size-length)
        if(length==0) return 0
        synchronized(lock) {
            while(true) {
                if(closed.get()) throw InterruptedIOException(Tr.text(UiText.PARALLEL_RECEIVE_STOPPED_105))
                failure?.let { throw it }
                if(consumedPosition>=endExclusive) return -1
                val c=chunkAt(consumedPosition)
                // Committed spans always reach Media3 through disk first.
                val cached=cachedLength(consumedPosition,minOf(length.toLong(),endExclusive-consumedPosition))
                if(cached>0) {
                    val count=store?.read(consumedPosition,buffer,destination,cached.toInt()) ?: -1
                    if(count>0) { advance(count);return count }
                }
                if(c!=null && c.bytes!=null && consumedPosition<c.position+c.available) {
                    val offset=(consumedPosition-c.position).toInt()
                    val count=minOf(length,c.available-offset)
                    c.bytes!!.copyInto(buffer,destination,offset,offset+count)
                    advance(count);return count
                }
                if(c!=null && c.done && c.bytes==null && !c.persisting) removeChunk(c)
                readWaiting=true;schedule()
                try { lock.wait(50) } catch(e:InterruptedException) {
                    Thread.currentThread().interrupt();throw InterruptedIOException(Tr.text(UiText.PARALLEL_RECEIVE_INTERRUPTED_118))
                } finally { readWaiting=false }
            }
        }
    }
    private fun advance(count:Int) {
        consumedPosition+=count
        if(!explicitPlaybackPosition) playbackPosition=consumedPosition
        val iterator=chunks.values.iterator()
        while(iterator.hasNext()) {
            val c=iterator.next()
            if(c.end>consumedPosition) break
            if(c.done && !c.persisting) { releaseArray(c);iterator.remove() }
        }
        schedule()
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
            opened.countDown();chunks.clear();executor.shutdownNow();diskExecutor?.shutdownNow();bufferedBytes.set(0);lock.notifyAll()
        }
    }
}
