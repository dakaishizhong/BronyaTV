package tv.ember.client

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.ember.client.cache.*
import tv.ember.client.network.RangePlaybackStatus
import tv.ember.client.network.RangePlaybackDataSource
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class DiskCacheDeviceTest {
    @Test fun foregroundBypassesAHoleLockedByReadAheadWithoutBlocking() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.cacheDir,"cache-lock-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated=object:ContextWrapper(context) {
            override fun getCacheDir()=folder
            override fun getApplicationContext():Context=this
        }
        val handle=requireNotNull(PlaybackDiskCache(isolated).configure(256,50_000_000,15))
        val fixture=File(folder,"source.bin").apply { writeBytes(ByteArray(1024) { it.toByte() }) }
        val key="locked-hole"
        val hole=handle.cache.startReadWrite(key,0,1024)
        val source=CacheDataSource.Factory().setCache(handle.cache).setUpstreamDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).createDataSource()
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result=executor.submit<ByteArray> {
                source.open(DataSpec.Builder().setUri(Uri.fromFile(fixture)).setKey(key).setLength(1024).build())
                val bytes=ByteArray(1024);var offset=0
                while(offset<bytes.size) offset+=source.read(bytes,offset,bytes.size-offset)
                bytes
            }.get(2,java.util.concurrent.TimeUnit.SECONDS)
            assertArrayEquals(fixture.readBytes(),result)
        } finally {
            handle.cache.releaseHoleSpan(hole);source.close();executor.shutdownNow();handle.cache.release();folder.deleteRecursively()
        }
    }
    @Test fun singleConnectionDetectsIgnoredRangesAndSubtitleLengthsDoNotReplaceVideoLength() {
        val url="https://example.test/video.mkv"
        fun upstream(code:Int,headers:Map<String,List<String>>)=DataSource.Factory {
            Proxy.newProxyInstance(HttpDataSource::class.java.classLoader,arrayOf(HttpDataSource::class.java)) { _,method,_->
                when(method.name) { "open" -> 16L;"getResponseCode" -> code;"getResponseHeaders" -> headers;else -> null }
            } as HttpDataSource
        }
        val status=RangePlaybackStatus(1,1048576)
        val ignored=RangePlaybackDataSource(upstream(200,mapOf("Content-Length" to listOf("1024"))),OkHttpClient(),url,emptyMap(),status)
        try {
            ignored.open(DataSpec.Builder().setUri(url).setPosition(128).setLength(16).build())
            assertTrue(status.rangeUnsupported);assertEquals(1024L,status.totalBytes)
        } finally { ignored.close() }
        val range=RangePlaybackStatus(1,1048576)
        val supported=RangePlaybackDataSource(upstream(206,mapOf("Content-Range" to listOf("bytes 128-143/2048"))),OkHttpClient(),url,emptyMap(),range)
        try {
            supported.open(DataSpec.Builder().setUri(url).setPosition(128).setLength(16).build())
            assertFalse(range.rangeUnsupported);assertEquals(2048L,range.totalBytes)
        } finally { supported.close() }
        val subtitle=RangePlaybackDataSource(upstream(200,mapOf("Content-Length" to listOf("16"))),OkHttpClient(),url,emptyMap(),range)
        try {
            subtitle.open(DataSpec.Builder().setUri("https://example.test/subtitle.srt").build())
            assertEquals(2048L,range.totalBytes)
        } finally { subtitle.close() }
    }
    private fun await(check: () -> Boolean) {
        val deadline=SystemClock.elapsedRealtime()+15_000
        while(SystemClock.elapsedRealtime()<deadline) { if(check()) return;Thread.sleep(50) }
        fail("Disk prefetch did not commit the requested window")
    }
    @Test fun realCacheCommitsAheadServesSeeksAndClearsItsFiles() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.cacheDir,"cache-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated=object:ContextWrapper(context) {
            override fun getCacheDir()=folder
            override fun getApplicationContext():Context=this
        }
        val manager=PlaybackDiskCache(isolated)
        var prefetch:DiskPrefetcher?=null
        val handle=requireNotNull(manager.configure(256,8_000_000,15))
        val fixture=File(folder,"source.bin")
        try {
            fixture.outputStream().buffered().use { out ->
                repeat(24*1024) { block -> out.write(ByteArray(1024) { (block%251).toByte() }) }
            }
            val url=Uri.fromFile(fixture).toString()
            val key="device-cache-test"
            val file=FileDataSource.Factory()
            val writable=CacheDataSource.Factory().setCache(handle.cache).setUpstreamDataSourceFactory(file)
                .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(handle.cache).setFragmentSize(2*DiskCachePlan.MIB))
                .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
            val range=RangePlaybackStatus(1,DiskCachePlan.MIB.toInt()).apply { totalBytes=fixture.length() }
            val worker=DiskPrefetcher(handle,writable,OkHttpClient(),url,key,range)
            prefetch=worker
            worker.seek(0)
            await { handle.cache.getCachedLength(key,0,4*DiskCachePlan.MIB)>=4*DiskCachePlan.MIB }
            assertTrue(worker.aheadBytes>=4*DiskCachePlan.MIB)
            assertTrue(manager.snapshot().usedBytes>0)
            assertTrue(handle.cache.keys.contains(key))
            val cacheOnly=CacheDataSource.Factory().setCache(handle.cache).setUpstreamDataSourceFactory(null)
                .setCacheWriteDataSinkFactory(null)
                .setEventListener(object:CacheDataSource.EventListener {
                    override fun onCacheIgnored(reason:Int) {}
                    override fun onCachedBytesRead(cacheSizeBytes:Long,cachedBytesRead:Long) { worker.hitBytes.addAndGet(cachedBytesRead) }
                })
            fun cachedRead(position:Long) {
                val source=DiskPlaybackDataSource(url,cacheOnly,DataSource.Factory { error("Main video used bypass source") },worker)
                try {
                    source.open(DataSpec.Builder().setUri(url).setPosition(position).setLength(1024).build())
                    val bytes=ByteArray(1024)
                    var read=0
                    while(read<bytes.size) {
                        val count=source.read(bytes,read,bytes.size-read)
                        assertTrue("Cached data ended too early",count>0);read+=count
                    }
                    assertArrayEquals(ByteArray(1024) { ((position/1024)%251).toByte() },bytes)
                } finally { source.close() }
            }
            cachedRead(1024)
            val end=22*DiskCachePlan.MIB
            worker.seek(end)
            await { handle.cache.getCachedLength(key,end,2*DiskCachePlan.MIB)>=2*DiskCachePlan.MIB }
            cachedRead(end)
            worker.seek(1024);cachedRead(1024)
            assertTrue(worker.hitBytes.get()>=3072)
            worker.close();prefetch=null
            manager.clear()
            assertEquals(0L,manager.snapshot().usedBytes)
            assertTrue(handle.cache.keys.isEmpty())
        } finally { prefetch?.close();handle.cache.release();folder.deleteRecursively() }
    }
}
