package tv.ember.client.cache

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.CacheDataSource
import okhttp3.OkHttpClient
import tv.ember.client.network.*
import java.io.IOException

/** Single-connection mode keeps a continuous HTTP body. Disk never adds per-chunk HTTP/TTFB overhead. */
@UnstableApi
class SingleDiskDataSource(private val factory:DataSource.Factory, private val client:OkHttpClient,
    private val url:String, private val headers:Map<String,String>, private val status:RangePlaybackStatus,
    private val disk:DiskPrefetcher, private val probeBytes:(Int)->Unit) : DataSource {
    private var delegate:DataSource?=null
    private val listeners=mutableListOf<TransferListener>()
    override fun addTransferListener(listener:TransferListener) { listeners+=listener;delegate?.addTransferListener(listener) }
    override fun open(spec:DataSpec):Long {
        val identity=try {
            ParallelRangeReader(client,url,headers+spec.httpRequestHeaders,spec.position,1,1,1,1) { n ->
                status.foregroundBytes.addAndGet(n.toLong());probeBytes(n)
            }.use { probe ->
                val length=probe.open();if(length>0) probe.read(ByteArray(1),0,1)
                fun header(name:String)=probe.responseHeaders.entries.firstOrNull { it.key.equals(name,true) }?.value?.firstOrNull()
                RangeIdentity(probe.totalBytes,header("ETag"),header("Last-Modified"))
            }
        } catch(e:RangeUnavailableException) {
            status.rangeUnsupported=true;status.mode=e.message ?: status.mode
            val source=factory.createDataSource();delegate=source;listeners.forEach(source::addTransferListener)
            return source.open(spec)
        } catch(e:RangeHttpException) {
            throw HttpDataSource.InvalidResponseCodeException(e.code,e.responseMessage,e,e.headers,spec,ByteArray(0))
        }
        status.totalBytes=identity.total
        val length=minOf(identity.total-spec.position,if(spec.length==C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else spec.length)
        if(length==0L) return 0
        val store=disk.validate(identity)
        val boundSpec=spec.buildUpon().setKey(disk.key).setLength(length).build()
        if(!store.enabled) {
            // Async invalidation must never expose old spans or hold up the foreground network.
            val source=Tee(factory.createDataSource(),store,identity,status)
            delegate=source;listeners.forEach(source::addTransferListener);source.open(boundSpec);return length
        }
        val cached=CacheDataSource.Factory().setCache(disk.handle.cache).setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setUpstreamDataSourceFactory { Tee(factory.createDataSource(),store,identity,status) }
            .setEventListener(object:CacheDataSource.EventListener {
                override fun onCacheIgnored(reason:Int) {}
                override fun onCachedBytesRead(cacheSizeBytes:Long,cachedBytesRead:Long) { disk.hitBytes.addAndGet(cachedBytesRead) }
            }).createDataSource()
        delegate=cached;listeners.forEach(cached::addTransferListener)
        cached.open(boundSpec)
        return length
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int)=if(length==0) 0 else delegate?.read(buffer,offset,length) ?: C.RESULT_END_OF_INPUT
    override fun getUri():Uri?=delegate?.uri
    override fun getResponseHeaders():Map<String,List<String>> = delegate?.responseHeaders ?: emptyMap()
    override fun close() { delegate?.close();delegate=null }

    private class Tee(private val source:DataSource,private val store:RangeChunkStore,private val identity:RangeIdentity,
        private val status:RangePlaybackStatus):DataSource {
        private val bytes=ByteArray(status.chunkBytes)
        private var filled=0
        private var start=0L
        private var active=false
        private var requestUri:Uri?=null
        private var remaining=0L
        override fun addTransferListener(listener:TransferListener)=source.addTransferListener(listener)
        override fun open(spec:DataSpec):Long {
            start=spec.position;filled=0;remaining=spec.length;requestUri=spec.uri;active=true;status.nativeRangeRequests.incrementAndGet()
            val validator=identity.etag?.takeUnless { it.startsWith("W/",true) } ?: identity.lastModified
            val requestHeaders=spec.httpRequestHeaders+mapOf("Accept-Encoding" to "identity")+(validator?.let { mapOf("If-Range" to it) } ?: emptyMap())
            val actual=spec.buildUpon().setFlags(spec.flags and DataSpec.FLAG_ALLOW_GZIP.inv()).setHttpRequestHeaders(requestHeaders).build()
            try {
                val length=source.open(actual)
                fun header(name:String)=source.responseHeaders.entries.firstOrNull { it.key.equals(name,true) }?.value?.firstOrNull()
                val http=source as? HttpDataSource ?: throw IOException("Disk video source must be HTTP")
                val end=spec.position+minOf(spec.length,identity.total-spec.position)
                if(http.responseCode==206) {
                    val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)",RegexOption.IGNORE_CASE).matchEntire(header("Content-Range").orEmpty())
                    if(match==null || match.groupValues[1].toLongOrNull()!=spec.position || match.groupValues[2].toLongOrNull()!=end-1 || match.groupValues[3].toLongOrNull()!=identity.total)
                        throw IOException("Range position or total changed")
                } else if(http.responseCode!=200 || spec.position!=0L || end!=identity.total) throw IOException("Server ignored the disk playback range")
                if(header("ETag")!=identity.etag || header("Last-Modified")!=identity.lastModified ||
                    (header("Content-Length")?.toLongOrNull()?.let { it!=end-spec.position }==true)) throw IOException("Source size or version changed")
                if(header("Content-Encoding").orEmpty().let { it.isNotEmpty() && !it.equals("identity",true) }) throw IOException("Compressed range response")
                return length
            } catch(e:Exception) { close();throw e }
        }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            val n=source.read(buffer,offset,length)
            if(n<0) { if(remaining>0) throw IOException("Range response ended early");flush();stopCounting();return n }
            if(n>remaining) throw IOException("Range response length mismatch")
            var cursor=0
            while(cursor<n) {
                val count=minOf(n-cursor,bytes.size-filled)
                buffer.copyInto(bytes,filled,offset+cursor,offset+cursor+count);filled+=count;cursor+=count
                if(filled==bytes.size) { store.offer(start,bytes);start+=filled;filled=0 }
            }
            remaining-=n
            if(remaining==0L) { flush();stopCounting() }
            return n
        }
        private fun flush() { if(filled>0) { store.offer(start,bytes.copyOf(filled));start+=filled;filled=0 } }
        private fun stopCounting() { if(active) { active=false;status.nativeRangeRequests.decrementAndGet() } }
        override fun getUri():Uri?=requestUri
        override fun getResponseHeaders()=source.responseHeaders
        override fun close() { if(active && remaining>0) status.cancelledForeground.incrementAndGet();try { flush();source.close() } finally { stopCounting() } }
    }
}
