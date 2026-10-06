package tv.ember.client.network

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import okhttp3.OkHttpClient
import java.io.IOException

class RangePlaybackStatus(val requestedConnections:Int,val budgetBytes:Int, val chunkBytes:Int=1024*1024, val aheadWindowBytes:Int=budgetBytes) {
    @Volatile var mode=Tr.text(UiText.WAITING_FOR_SOURCE_120)
    @Volatile var reader:ParallelRangeReader?=null
    @Volatile var store:RangeChunkStore?=null
    @Volatile var loadingDemand=true; private set
    fun setLoadingDemand(value:Boolean) { loadingDemand=value;reader?.setLoadingDemand(value) }
    val foregroundBytes=java.util.concurrent.atomic.AtomicLong()
    val backgroundBytes=java.util.concurrent.atomic.AtomicLong()
    val cancelledForeground=java.util.concurrent.atomic.AtomicLong()
    val cancelledBackground=java.util.concurrent.atomic.AtomicLong()
    val nativeRangeRequests=java.util.concurrent.atomic.AtomicInteger()
    val duplicatedRangeRequests=java.util.concurrent.atomic.AtomicLong()
    @Volatile var rangeUnsupported=false
    @Volatile var totalBytes=-1L
    data class Rates(val foreground:Long,val background:Long)
    private var rateAt=System.nanoTime()
    private var lastForeground=0L
    private var lastBackground=0L
    private var rates=Rates(0,0)
    @Synchronized fun throughput():Rates {
        val now=System.nanoTime();val elapsed=now-rateAt
        if(elapsed<250_000_000) return rates
        val foreground=foregroundBytes.get()+(reader?.foregroundBytes?.get() ?: 0)
        val background=backgroundBytes.get()+(reader?.backgroundBytes?.get() ?: 0)
        rates=Rates(((foreground-lastForeground)*1e9/elapsed).toLong().coerceAtLeast(0),((background-lastBackground)*1e9/elapsed).toLong().coerceAtLeast(0))
        lastForeground=foreground;lastBackground=background;rateAt=now;return rates
    }
    val bufferedBytes:Long get()=reader?.bufferedBytes?.get() ?: 0
}

@UnstableApi
class RangePlaybackDataSource(private val singleFactory:DataSource.Factory,private val client:OkHttpClient,
                              private val videoUrl:String,private val headers:Map<String,String>,
                              private val status:RangePlaybackStatus) : BaseDataSource(true) {
    private var reader:ParallelRangeReader?=null
    private var single:DataSource?=null
    private var spec:DataSpec?=null
    private var open=false
    private val transferLock=Any()
    private fun transferred(n:Int) = synchronized(transferLock) { if(open) bytesTransferred(n) }
    override fun open(dataSpec:DataSpec):Long {
        spec=dataSpec;transferInitializing(dataSpec)
        synchronized(transferLock) { open=true;transferStarted(dataSpec) }
        val main=dataSpec.uri.toString()==videoUrl
        if(main && !status.rangeUnsupported && status.requestedConnections>1 && dataSpec.httpMethod==DataSpec.HTTP_METHOD_GET && dataSpec.length!=0L &&
            status.aheadWindowBytes >= status.chunkBytes &&
            !StreamPolicy.isPlaylist(videoUrl)) {
            val r=ParallelRangeReader(client,videoUrl,headers+dataSpec.httpRequestHeaders,dataSpec.position,dataSpec.length,
                status.requestedConnections,status.chunkBytes,status.aheadWindowBytes,status.store) { n ->
                    transferred(n)
                }
            reader=r;status.reader=r
            try { val length=r.open();r.setLoadingDemand(status.loadingDemand);status.totalBytes=r.totalBytes;status.mode=Tr.text(UiText.INDEPENDENT_TCP_RANGE_CONNECTIONS_121 ,(status.requestedConnections));return length }
            catch(e:RangeUnavailableException) { r.close();reader=null;status.reader=null;status.rangeUnsupported=true;status.mode=e.message ?: Tr.text(UiText.SINGLE_CONNECTION_FALLBACK_122) }
            catch(e:RangeHttpException) { throw httpFailure(e,dataSpec) }
        } else if(main && !status.rangeUnsupported) status.mode=Tr.text(UiText.SINGLE_CONNECTION_123)
        val disk=status.store as? tv.ember.client.cache.DiskPrefetcher
        val s=if(main && disk!=null && !status.rangeUnsupported && status.requestedConnections==1 &&
            status.budgetBytes>=3L*status.chunkBytes && dataSpec.httpMethod==DataSpec.HTTP_METHOD_GET && !StreamPolicy.isPlaylist(videoUrl))
            tv.ember.client.cache.SingleDiskDataSource(singleFactory,client,videoUrl,headers,status,disk,::transferred)
        else singleFactory.createDataSource()
        single=s
        s.addTransferListener(object:TransferListener {
            override fun onTransferInitializing(source:DataSource,spec:DataSpec,network:Boolean) {}
            override fun onTransferStart(source:DataSource,spec:DataSpec,network:Boolean) {}
            override fun onTransferEnd(source:DataSource,spec:DataSpec,network:Boolean) {}
            override fun onBytesTransferred(source:DataSource,spec:DataSpec,network:Boolean,bytes:Int) { if(network) { if(main) status.foregroundBytes.addAndGet(bytes.toLong());transferred(bytes) } }
        })
        val length = s.open(dataSpec)
        if(main) {
            fun header(name:String)=s.responseHeaders.entries.firstOrNull { it.key.equals(name,true) }?.value?.firstOrNull()
            // A broken probe may select native fallback. OkHttpDataSource itself does not reject
            // a mismatched 206 offset; validate it before any fallback bytes reach the extractor.
            if(s is HttpDataSource && s.responseCode==206 && !StreamPolicy.isPlaylist(videoUrl,header("Content-Type").orEmpty())) {
                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)",RegexOption.IGNORE_CASE).matchEntire(header("Content-Range").orEmpty())
                val from=match?.groupValues?.get(1)?.toLongOrNull()
                val to=match?.groupValues?.get(2)?.toLongOrNull()
                val total=match?.groupValues?.get(3)?.toLongOrNull()
                val expectedEnd=total?.let { dataSpec.position+minOf(it-dataSpec.position,
                    if(dataSpec.length==C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else dataSpec.length)-1 }
                if(from!=dataSpec.position || to==null || total==null || total<=to || to<from || to!=expectedEnd ||
                    header("Content-Length")?.toLongOrNull()?.let { it!=to-from+1 }==true ||
                    header("Content-Encoding").orEmpty().let { it.isNotEmpty() && !it.equals("identity",true) }) {
                    throw IOException(Tr.text(UiText.RANGE_POSITION_MISMATCH_MERGING_STOPPED_111))
                }
            }
            header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { status.totalBytes=it }
            if(s is HttpDataSource && s.responseCode==200) {
                header("Content-Length")?.toLongOrNull()?.let { status.totalBytes=it }
                if(dataSpec.position>0 || dataSpec.length!=C.LENGTH_UNSET.toLong()) {
                    status.rangeUnsupported=true
                    status.mode=Tr.text(UiText.SERVER_IGNORED_RANGE_SINGLE_CONNECTION_124)
                }
            }
            if(dataSpec.length==C.LENGTH_UNSET.toLong() && length>=0) status.totalBytes=dataSpec.position+length
        }
        return length
    }
    private fun httpFailure(e:RangeHttpException,s:DataSpec)=HttpDataSource.InvalidResponseCodeException(
        e.code,e.responseMessage,e,e.headers,s,ByteArray(0))
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        return try { reader?.read(buffer,offset,length) ?: single?.read(buffer,offset,length) ?: C.RESULT_END_OF_INPUT }
        catch(e:RangeHttpException) { throw httpFailure(e,requireNotNull(spec)) }
    }
    // Progressive extractors reuse this URI for seeks. Keep the main stream identity
    // stable after redirects, otherwise later opens bypass the parallel/video path.
    // Auxiliary manifests still expose their resolved URI for relative segments.
    override fun getUri():Uri? {
        val contentType=responseHeaders.entries.firstOrNull { it.key.equals("Content-Type",true) }?.value?.firstOrNull().orEmpty()
        return if(spec?.uri?.toString()==videoUrl && !StreamPolicy.isPlaylist(videoUrl,contentType)) spec?.uri
        else reader?.resolvedUrl?.let(Uri::parse) ?: single?.uri
    }
    override fun getResponseHeaders():Map<String,List<String>> = reader?.responseHeaders ?: single?.responseHeaders ?: emptyMap()
    override fun close() {
        synchronized(transferLock) { if(open) { open=false;transferEnded() } }
        if(status.reader===reader) status.reader=null
        reader?.let { r ->
            r.close();status.foregroundBytes.addAndGet(r.foregroundBytes.get());status.backgroundBytes.addAndGet(r.backgroundBytes.get())
            status.cancelledForeground.addAndGet(r.cancelledForeground.get());status.cancelledBackground.addAndGet(r.cancelledBackground.get())
            status.duplicatedRangeRequests.addAndGet(r.duplicatedRangeRequests.get())
        };reader=null;single?.close();single=null;spec=null
    }
}
