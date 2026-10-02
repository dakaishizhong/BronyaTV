package tv.ember.client.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import okhttp3.OkHttpClient

class RangePlaybackStatus(val requestedConnections:Int,val budgetBytes:Int) {
    @Volatile var mode="等待片源"
    @Volatile var reader:ParallelRangeReader?=null
    @Volatile var rangeUnsupported=false
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
            status.budgetBytes >= 65536*(status.requestedConnections+1) &&
            !videoUrl.substringBefore('?').endsWith(".m3u8",true)) {
            val chunk=(status.budgetBytes/(status.requestedConnections+1)/65536*65536).coerceIn(65536,2*1024*1024)
            val r=ParallelRangeReader(client,videoUrl,headers+dataSpec.httpRequestHeaders,dataSpec.position,dataSpec.length,
                status.requestedConnections,chunk,::transferred)
            reader=r;status.reader=r
            try { val length=r.open();status.mode="独立 TCP ${status.requestedConnections} 路分段";return length }
            catch(e:RangeUnavailableException) { r.close();reader=null;status.reader=null;status.rangeUnsupported=true;status.mode=e.message ?: "单连接回退" }
            catch(e:RangeHttpException) { throw httpFailure(e,dataSpec) }
        } else if(main && !status.rangeUnsupported) status.mode="单连接"
        val s=singleFactory.createDataSource();single=s
        s.addTransferListener(object:TransferListener {
            override fun onTransferInitializing(source:DataSource,spec:DataSpec,network:Boolean) {}
            override fun onTransferStart(source:DataSource,spec:DataSpec,network:Boolean) {}
            override fun onTransferEnd(source:DataSource,spec:DataSpec,network:Boolean) {}
            override fun onBytesTransferred(source:DataSource,spec:DataSpec,network:Boolean,bytes:Int) { if(network) transferred(bytes) }
        })
        return s.open(dataSpec)
    }
    private fun httpFailure(e:RangeHttpException,s:DataSpec)=HttpDataSource.InvalidResponseCodeException(
        e.code,e.responseMessage,e,e.headers,s,ByteArray(0))
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        return try { reader?.read(buffer,offset,length) ?: single?.read(buffer,offset,length) ?: C.RESULT_END_OF_INPUT }
        catch(e:RangeHttpException) { throw httpFailure(e,requireNotNull(spec)) }
    }
    override fun getUri():Uri?=reader?.resolvedUrl?.let(Uri::parse) ?: single?.uri
    override fun getResponseHeaders():Map<String,List<String>> = reader?.responseHeaders ?: single?.responseHeaders ?: emptyMap()
    override fun close() {
        synchronized(transferLock) { if(open) { open=false;transferEnded() } }
        if(status.reader===reader) status.reader=null
        reader?.close();reader=null;single?.close();single=null;spec=null
    }
}
