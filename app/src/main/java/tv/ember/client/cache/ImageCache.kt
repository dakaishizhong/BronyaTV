package tv.ember.client.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import tv.ember.client.data.Session
import tv.ember.client.network.HttpClient
import tv.ember.client.network.awaitResponse

class ImageCache(private val context: Context,capacity: ()->Long) {
    private val disk=BoundedDiskStore(java.io.File(context.cacheDir,"images-v1"),capacity)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val requests=Semaphore(3)
    private val pending=SharedRequests<String,Bitmap?>(scope)
    private val encoded=SharedRequests<String,ByteArray?>(scope)
    private val downloads=java.util.concurrent.atomic.AtomicInteger()
    private val active=java.util.concurrent.atomic.AtomicInteger()
    private val peak=java.util.concurrent.atomic.AtomicInteger()
    private val diskHits=java.util.concurrent.atomic.AtomicInteger()
    data class Snapshot(val networkRequests: Int,val diskHits: Int,val activeRequests: Int,val peakRequests: Int,val memoryBytes: Int,val memoryBudget: Int)
    fun snapshot()=Snapshot(downloads.get(),diskHits.get(),active.get(),peak.get(),memory.size(),memory.maxSize())
    private val normal=(Runtime.getRuntime().maxMemory()/32).coerceIn(2L*1024*1024,12L*1024*1024).toInt()
    private val memory=object: LruCache<String,Bitmap>(normal) { override fun sizeOf(key:String,value:Bitmap)=value.byteCount }
    @Volatile private var generation=0L
    suspend fun load(session: Session,url: String,width: Int,height: Int): Bitmap? {
        val diskKey=BoundedDiskStore.namespace(session.server,session.userId)+":"+url
        val key="$diskKey:${width}x$height"
        memory.get(key)?.let { return it }
        return pending.get(key) {
            val epoch=generation
            requests.withPermit {
                val count=active.incrementAndGet();synchronized(peak) { peak.set(maxOf(peak.get(),count)) }
                try {
                    // Encoded data is shared across display sizes; decoded bitmaps remain size-specific.
                    val bytes=encoded.get(diskKey) {
                        disk.read(diskKey)?.also { diskHits.incrementAndGet() } ?: run {
                            downloads.incrementAndGet()
                            HttpClient.api.newCall(Request.Builder().url(url).header("X-Emby-Token",session.token).build()).awaitResponse().use { response ->
                                if(!response.isSuccessful) null else response.body?.source()?.let { source ->
                                    if(source.request(4L*1024*1024+1)) null else source.readByteArray()
                                }
                            }?.also { synchronized(this@ImageCache) { if(epoch==generation) runCatching { disk.write(diskKey,it) } } }
                        }
                    }
                    if(bytes==null) null else withContext(Dispatchers.Default) {
                        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                        var sample=1
                        while(bounds.outWidth/sample>width.coerceAtLeast(1)*2 || bounds.outHeight/sample>height.coerceAtLeast(1)*2) sample*=2
                        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })
                        bitmap?.also { synchronized(this@ImageCache) { if(epoch==generation) memory.put(key,it) } }
                    }
                } catch(e: CancellationException) { throw e } catch(_: Exception) { null } finally { active.decrementAndGet() }
            }
        }
    }
    suspend fun usedBytes()=withContext(Dispatchers.IO) { disk.usedBytes() }
    suspend fun resize()=withContext(Dispatchers.IO) { disk.trim() }
    suspend fun clear() {
        synchronized(this) { generation++ };pending.cancel();encoded.cancel()
        memory.evictAll();withContext(Dispatchers.IO) { disk.clear() }
    }
    fun playback(active: Boolean) {
        val info=android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
        memory.resize(if(info.lowMemory) minOf(normal,2*1024*1024) else if(active) minOf(normal,3*1024*1024) else normal)
    }
    fun trim(critical: Boolean=false) { memory.resize(minOf(normal,2*1024*1024));if(critical) memory.evictAll() }
}
