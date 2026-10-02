package tv.ember.client.network

import okhttp3.*
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RangeUnavailableException(message: String) : IOException(message)
class RangeHttpException(val code: Int, val responseMessage: String, val headers: Map<String,List<String>>) : IOException("HTTP $code")

/** Bounded in-memory, ordered playback prefetch. Every range uses a separate HTTP/1.1 request. */
class ParallelRangeReader(private val client:OkHttpClient, private val url:String, private val headers:Map<String,String>,
                          private val start:Long, private val requestedLength:Long, private val connections:Int,
                          private val chunkBytes:Int, private val onBytes:(Int)->Unit) : AutoCloseable {
    private data class Chunk(val bytes:ByteArray)
    private val executor=Executors.newFixedThreadPool(connections) { task -> Thread(task,"EmberRange").apply { isDaemon=true } }
    private val calls=java.util.Collections.newSetFromMap(ConcurrentHashMap<Call,Boolean>())
    private val closed=AtomicBoolean()
    private val pending=ArrayDeque<Future<Chunk>>()
    private val queueLock=Any()
    val bufferedBytes=AtomicLong()
    var responseHeaders:Map<String,List<String>> = emptyMap();private set
    var resolvedUrl=url;private set
    private var total=0L
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
        if(amount>0 && closed.get()) throw InterruptedIOException("分段接收已停止")
        bufferedBytes.set((bufferedBytes.get()+amount).coerceAtLeast(0))
    }
    private fun fill() = synchronized(queueLock) {
        while(!closed.get() && pending.size<connections && next<endExclusive) {
            val position=next
            next=minOf(endExclusive,next+chunkBytes)
            pending.addLast(executor.submit<Chunk> { fetch(position,initial=false) })
        }
    }
    private fun fetch(position:Long,initial:Boolean):Chunk {
        if(closed.get()) throw InterruptedIOException("分段接收已停止")
        val limit=if(initial) {
            if(requestedLength>=0) minOf(start+requestedLength,position+chunkBytes) else position+chunkBytes
        } else minOf(endExclusive,position+chunkBytes)
        val request=Request.Builder().url(url)
        headers.forEach { (k,v)->request.header(k,v) }
        request.header("Accept-Encoding","identity").header("Range","bytes=$position-${limit-1}")
        if(!initial && validator!=null) request.header("If-Range",validator!!)
        val call=client.newCall(request.build());calls.add(call)
        var allocated=0
        try {
            if(closed.get()) { call.cancel();throw InterruptedIOException("分段接收已停止") }
            return call.execute().use { response ->
                if(response.code!=206) {
                    if(initial && response.code==200) throw RangeUnavailableException("服务器未支持分段 Range，使用单连接")
                    if(response.code==200) throw IOException("片源或 Range 响应发生变化，请重新获取地址")
                    throw RangeHttpException(response.code,response.message,response.headers.toMultimap())
                }
                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)",RegexOption.IGNORE_CASE).matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw if(initial) RangeUnavailableException("服务器没有有效 Content-Range，使用单连接") else IOException("无效 Content-Range")
                val from=match.groupValues[1].toLong();val to=match.groupValues[2].toLong();val size=match.groupValues[3].toLong()
                if(from!=position || to!=minOf(limit,size)-1 || size<=to || to<from) {
                    throw if(initial) RangeUnavailableException("服务器分段位置不匹配，使用单连接") else IOException("分段位置不匹配，停止合并")
                }
                if(response.header("Content-Encoding").orEmpty().let { it.isNotEmpty() && !it.equals("identity",true) })
                    throw RangeUnavailableException("服务器压缩了分段响应，使用单连接")
                if(initial) {
                    total=size;etag=response.header("ETag");modified=response.header("Last-Modified")
                    validator=etag?.takeUnless { it.startsWith("W/",true) } ?: modified
                    responseHeaders=response.headers.toMultimap();resolvedUrl=response.request.url.toString()
                } else if(size!=total || response.header("ETag")!=etag || response.header("Last-Modified")!=modified) {
                    throw IOException("片源大小或版本发生变化，停止合并")
                }
                val length=(to-from+1).toInt()
                if(closed.get()) throw InterruptedIOException("分段接收已停止")
                val bytes=ByteArray(length);allocated=length;adjustBuffered(length.toLong())
                val body=response.body ?: throw IOException("空分段响应")
                if(body.contentLength()>=0 && body.contentLength()!=length.toLong()) throw IOException("分段响应长度不一致")
                body.byteStream().use { input ->
                    var cursor=0
                    while(cursor<length) {
                        if(closed.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("分段接收已停止")
                        val n=input.read(bytes,cursor,minOf(65536,length-cursor))
                        if(n<0) throw IOException("分段响应提前结束")
                        if(n==0) continue
                        cursor+=n;onBytes(n)
                    }
                }
                allocated=0;Chunk(bytes)
            }
        } finally {
            if(allocated>0) adjustBuffered(-allocated.toLong())
            calls.remove(call)
        }
    }
    fun read(buffer:ByteArray,destination:Int,length:Int):Int {
        if(length==0) return 0
        if(closed.get()) throw InterruptedIOException("分段接收已停止")
        var chunk=current
        while(chunk==null || offset==chunk.bytes.size) {
            chunk?.let { consumed -> adjustBuffered(-consumed.bytes.size.toLong()) }
            current=null;offset=0
            val future=synchronized(queueLock) { if(pending.isEmpty()) null else pending.removeFirst() } ?: return -1
            chunk=try { future.get() } catch(e:ExecutionException) { throw (e.cause as? IOException ?: IOException("分段请求失败",e.cause)) }
                catch(e:InterruptedException) { Thread.currentThread().interrupt();throw InterruptedIOException("分段接收已中断") }
                catch(e:CancellationException) { throw InterruptedIOException("分段接收已取消") }
            if(closed.get()) throw InterruptedIOException("分段接收已停止")
            current=chunk;fill()
        }
        val n=minOf(length,chunk.bytes.size-offset)
        chunk.bytes.copyInto(buffer,destination,offset,offset+n);offset+=n
        return n
    }

    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        calls.forEach { it.cancel() }
        synchronized(queueLock) {
            pending.forEach { it.cancel(true) };pending.clear();executor.shutdownNow()
        }
        current=null
        synchronized(bufferedBytes) { bufferedBytes.set(0) }
    }
}
