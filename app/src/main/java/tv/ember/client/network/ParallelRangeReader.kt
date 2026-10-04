package tv.ember.client.network

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import okhttp3.*
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RangeUnavailableException(message: String) : IOException(message)
class RangeHttpException(val code: Int, val responseMessage: String, val headers: Map<String,List<String>>) : IOException("HTTP ${code}")

/** Bounded in-memory, ordered playback prefetch. Every range uses a separate HTTP/1.1 request. */
class ParallelRangeReader(private val client:OkHttpClient, private val url:String, private val headers:Map<String,String>,
                          private val start:Long, private val requestedLength:Long, private val connections:Int,
                          private val chunkBytes:Int, private val onBytes:(Int)->Unit) : AutoCloseable {
    private class Chunk(val bytes:ByteArray) {
        val lock = Object()
        var available = 0
        var done = false
        var failure: IOException? = null
    }
    private val executor=Executors.newFixedThreadPool(connections) { task -> Thread(task,"BronyaTVRange").apply { isDaemon=true } }
    private val calls=java.util.Collections.newSetFromMap(ConcurrentHashMap<Call,Boolean>())
    private val closed=AtomicBoolean()
    private val pending=ArrayDeque<Chunk>()
    private val queueLock=Any()
    private val recycled=ArrayDeque<ByteArray>()
    val bufferedBytes=AtomicLong()
    var responseHeaders:Map<String,List<String>> = emptyMap();private set
    var resolvedUrl=url;private set
    private var total=0L
    val totalBytes: Long get() = total
    private var endExclusive=0L
    private var next=0L
    @Volatile private var current:Chunk?=null
    private var offset=0
    private var validator:String?=null
    private var etag:String?=null
    private var modified:String?=null
    init { require(connections in 2..8 && chunkBytes>0 && start>=0 && requestedLength>=-1 && requestedLength!=0L) }
    fun open():Long {
        try {
            current=fetch(start,initial=true)
            endExclusive=if(requestedLength<0) total else minOf(total,start+requestedLength)
            next=start+current!!.bytes.size
            fill()
            return endExclusive-start
        } catch(e:Exception) { close();throw e }
    }
    private fun adjustBuffered(amount:Long) = synchronized(bufferedBytes) {
        if(amount>0 && closed.get()) throw InterruptedIOException(Tr.text(UiText.PARALLEL_RECEIVE_STOPPED_105))
        bufferedBytes.set((bufferedBytes.get()+amount).coerceAtLeast(0))
    }
    private fun fill() = synchronized(queueLock) {
        while(!closed.get() && pending.size<connections && next<endExclusive) {
            val position=next
            next=minOf(endExclusive,next+chunkBytes)
            val length=(next-position).toInt()
            val bytes=if(length==chunkBytes && recycled.isNotEmpty()) recycled.removeFirst() else ByteArray(length)
            val chunk = Chunk(bytes)
            adjustBuffered(chunk.bytes.size.toLong())
            pending.addLast(chunk)
            executor.execute {
                try { fetch(position, initial=false, target=chunk) }
                catch(e:Exception) { synchronized(chunk.lock) { chunk.failure=e as? IOException ?: IOException(Tr.text(UiText.RANGE_REQUEST_FAILED_117),e) } }
                finally { synchronized(chunk.lock) { chunk.done=true;chunk.lock.notifyAll() } }
            }
        }
    }
    private fun fetch(position:Long,initial:Boolean,target:Chunk?=null):Chunk {
        if(closed.get()) throw InterruptedIOException(Tr.text(UiText.PARALLEL_RECEIVE_STOPPED_105))
        val limit=if(initial) {
            // Start with a small probe. Waiting for an entire multi-MiB chunk here
            // serializes startup and every seek before any other worker can start.
            val probe=minOf(chunkBytes,64*1024)
            if(requestedLength>=0) minOf(start+requestedLength,position+probe) else position+probe
        } else minOf(endExclusive,position+chunkBytes)
        val request=Request.Builder().url(url)
        headers.forEach { (k,v)->request.header(k,v) }
        request.header("Accept-Encoding","identity").header("Range","bytes=${position}-${limit-1}")
        if(!initial && validator!=null) request.header("If-Range",validator!!)
        val call=client.newCall(request.build());calls.add(call)
        var allocated=0
        try {
            if(closed.get()) { call.cancel();throw InterruptedIOException(Tr.text(UiText.PARALLEL_RECEIVE_STOPPED_105)) }
            return call.execute().use { response ->
                if(initial && response.code==416) {
                    val size=Regex("bytes \\*/(\\d+)",RegexOption.IGNORE_CASE).matchEntire(response.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                    // Like Media3's HTTP source, an exact EOF reopen is an empty successful range.
                    // Missing/malformed totals and positions beyond EOF remain HTTP failures.
                    if(size!=null && position==size) {
                        total=size;responseHeaders=response.headers.toMultimap();resolvedUrl=response.request.url.toString()
                        return@use Chunk(ByteArray(0)).apply { done=true }
                    }
                }
                if(response.code!=206) {
                    if(initial && response.code==200) throw RangeUnavailableException(Tr.text(UiText.SERVER_DOES_NOT_SUPPORT_PARALLEL_RANGE_106))
                    if(response.code==200) throw IOException(Tr.text(UiText.SOURCE_OR_RANGE_RESPONSE_CHANGED_REFRESH_107))
                    throw RangeHttpException(response.code,response.message,response.headers.toMultimap())
                }
                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)",RegexOption.IGNORE_CASE).matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw if(initial) RangeUnavailableException(Tr.text(UiText.SERVER_HAS_NO_VALID_CONTENT_RANGE_108)) else IOException(Tr.text(UiText.INVALID_CONTENT_RANGE_109))
                val from=match.groupValues[1].toLong();val to=match.groupValues[2].toLong();val size=match.groupValues[3].toLong()
                if(from!=position || to!=minOf(limit,size)-1 || size<=to || to<from) {
                    throw if(initial) RangeUnavailableException(Tr.text(UiText.SERVER_RETURNED_A_DIFFERENT_RANGE_POSITION_110)) else IOException(Tr.text(UiText.RANGE_POSITION_MISMATCH_MERGING_STOPPED_111))
                }
                if(response.header("Content-Encoding").orEmpty().let { it.isNotEmpty() && !it.equals("identity",true) })
                    throw RangeUnavailableException(Tr.text(UiText.SERVER_COMPRESSED_THE_RANGE_RESPONSE_USING_112))
                if(initial) {
                    total=size;etag=response.header("ETag");modified=response.header("Last-Modified")
                    validator=etag?.takeUnless { it.startsWith("W/",true) } ?: modified
                    responseHeaders=response.headers.toMultimap();resolvedUrl=response.request.url.toString()
                } else if(size!=total || response.header("ETag")!=etag || response.header("Last-Modified")!=modified) {
                    throw IOException(Tr.text(UiText.SOURCE_SIZE_OR_VERSION_CHANGED_MERGING_113))
                }
                val length=(to-from+1).toInt()
                if(closed.get()) throw InterruptedIOException(Tr.text(UiText.PARALLEL_RECEIVE_STOPPED_105))
                val chunk = target ?: Chunk(ByteArray(length)).also { allocated=length;adjustBuffered(length.toLong()) }
                val bytes=chunk.bytes
                check(bytes.size==length)
                val body=response.body ?: throw IOException(Tr.text(UiText.EMPTY_RANGE_RESPONSE_114))
                if(body.contentLength()>=0 && body.contentLength()!=length.toLong()) throw IOException(Tr.text(UiText.INCONSISTENT_RANGE_RESPONSE_LENGTH_115))
                body.byteStream().use { input ->
                    var cursor=0
                    while(cursor<length) {
                        if(closed.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException(Tr.text(UiText.PARALLEL_RECEIVE_STOPPED_105))
                        val n=input.read(bytes,cursor,minOf(65536,length-cursor))
                        if(n<0) throw IOException(Tr.text(UiText.RANGE_RESPONSE_ENDED_EARLY_116))
                        if(n==0) continue
                        cursor+=n;onBytes(n)
                        synchronized(chunk.lock) { chunk.available=cursor;chunk.lock.notifyAll() }
                    }
                }
                allocated=0;chunk.apply { synchronized(lock) { done=true;lock.notifyAll() } }
            }
        } finally {
            if(allocated>0) adjustBuffered(-allocated.toLong())
            calls.remove(call)
        }
    }
    fun read(buffer:ByteArray,destination:Int,length:Int):Int {
        require(destination>=0 && length>=0 && destination<=buffer.size-length)
        if(length==0) return 0
        while(true) {
            if(closed.get()) throw InterruptedIOException(Tr.text(UiText.PARALLEL_RECEIVE_STOPPED_105))
            val chunk=current ?: return -1
            synchronized(chunk.lock) {
                while(offset==chunk.available && !chunk.done && !closed.get()) {
                    try { chunk.lock.wait() } catch(e:InterruptedException) {
                        Thread.currentThread().interrupt();throw InterruptedIOException(Tr.text(UiText.PARALLEL_RECEIVE_INTERRUPTED_118))
                    }
                }
                if(closed.get()) throw InterruptedIOException(Tr.text(UiText.PARALLEL_RECEIVE_STOPPED_105))
                if(offset<chunk.available) {
                    val n=minOf(length,chunk.available-offset)
                    chunk.bytes.copyInto(buffer,destination,offset,offset+n);offset+=n
                    return n
                }
                chunk.failure?.let { throw it }
            }
            adjustBuffered(-chunk.bytes.size.toLong())
            current=synchronized(queueLock) {
                if(chunk.bytes.size==chunkBytes) recycled.addLast(chunk.bytes)
                if(pending.isEmpty()) null else pending.removeFirst()
            }
            offset=0
            fill()
        }
    }

    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        calls.forEach { it.cancel() }
        synchronized(queueLock) {
            (pending.toList()+listOfNotNull(current)).forEach { chunk -> synchronized(chunk.lock) { chunk.lock.notifyAll() } }
            pending.clear();recycled.clear();executor.shutdownNow()
        }
        current=null
        synchronized(bufferedBytes) { bufferedBytes.set(0) }
    }
}
