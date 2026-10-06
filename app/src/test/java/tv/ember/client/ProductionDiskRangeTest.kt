package tv.ember.client

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.ember.client.cache.*
import tv.ember.client.network.*
import java.nio.file.Files

/** Exercises the production SimpleCache adapter, real filesystem and the same real HTTP/TCP fixture on API 23. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23],manifest=Config.NONE,application=Application::class)
class ProductionDiskRangeTest {
    private fun withCache(test:(PlaybackDiskCache.Handle)->Unit) {
        val folder=Files.createTempDirectory("bronya-simple-cache-").toFile()
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext():Context=this
            override fun getCacheDir()=folder
        }
        val handle=requireNotNull(PlaybackDiskCache(context).configure(256,80_000_000,15))
        try { test(handle) } finally { handle.cache.release();folder.deleteRecursively() }
    }
    @Test fun productionDiskCacheKeepsFiftyEightyAndHundredMbpsConsumersFed() {
        for(mbps in listOf(50,80,100)) withCache { handle ->
            val plan=StreamPolicy.create(8,mbps*1_000_000L,512L*1048576,32L*1048576,false,true)
            val status=RangePlaybackStatus(plan.connections,plan.budgetBytes,plan.chunkBytes,plan.aheadWindowBytes)
            val disk=DiskPrefetcher(handle,"production-$mbps",status)
            val store=object:RangeBenchmarkStore,RangeChunkStore by disk {
                override val pendingCopyBytes get()=disk.pendingWriteBytes
                override fun close() {}
            }
            try {
                RangePipelineBenchmarkTest().runCase(mbps,200,8,true,store)
                assertTrue("actual SimpleCache spans were committed",handle.cache.cacheSpace>0)
                // Short clips can fit entirely in the RAM window; explicitly verify the committed seek bytes too.
                val span=handle.cache.getCachedSpans("production-$mbps").first()
                val actual=ByteArray(minOf(span.length,65536).toInt())
                assertEquals(actual.size,disk.read(span.position,actual,0,actual.size))
                assertArrayEquals(ByteArray(actual.size) { val x=(span.position+it).toInt();((x*31+x/7919)%251).toByte() },actual)
                assertTrue(disk.hitBytes.get()>0)
            } finally { disk.close() }
        }
    }
    @Test fun singleModeUsesOneContinuousBodyAndReusesValidatedDiskSpans()=withCache { handle ->
        val fixture=ByteArray(32*1048576) { (it%251).toByte() }
        val content=java.util.concurrent.atomic.AtomicReference(fixture)
        val version=java.util.concurrent.atomic.AtomicReference("v1")
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
                    assertEquals("/original.mkv?sig=fixture",request.path)
                    val parts=requireNotNull(request.getHeader("Range")).removePrefix("bytes=").split('-')
                    val start=parts[0].toInt();val end=minOf(parts[1].toInt(),fixture.lastIndex)
                    return okhttp3.mockwebserver.MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${fixture.size}")
                        .setHeader("ETag",version.get()).setBody(okio.Buffer().write(content.get(),start,end-start+1))
                        .setHeadersDelay(200,java.util.concurrent.TimeUnit.MILLISECONDS).throttleBody(65536,5,java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
            val client=okhttp3.OkHttpClient.Builder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
            val factory=androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client)
            val status=RangePlaybackStatus(1,16*1048576,2*1048576,8*1048576)
            val disk=DiskPrefetcher(handle,"single",status)
            val url=server.url("/original.mkv?sig=fixture").toString()
            fun read(position:Long,length:Long):ByteArray {
                disk.seek(position)
                val source=SingleDiskDataSource(factory,client,url,emptyMap(),status,disk) {}
                try {
                    assertEquals(length,source.open(androidx.media3.datasource.DataSpec.Builder().setUri(url).setPosition(position).setLength(length).build()))
                    val out=java.io.ByteArrayOutputStream();val bytes=ByteArray(65536)
                    while(true) { val count=source.read(bytes,0,bytes.size);if(count<0) break;out.write(bytes,0,count) }
                    return out.toByteArray()
                } finally { source.close() }
            }
            try {
                val before=System.nanoTime();assertArrayEquals(fixture,read(0,fixture.size.toLong()))
                assertTrue("disk writing must not add per-chunk TTFB",System.nanoTime()-before<5_000_000_000L)
                assertEquals("one identity probe and one continuous body",2,server.requestCount)
                val deadline=System.nanoTime()+5_000_000_000L
                while(!disk.contains(1048576,1048576) && System.nanoTime()<deadline) Thread.sleep(2)
                assertTrue(disk.contains(1048576,1048576))
                assertArrayEquals(fixture.copyOfRange(1048576,2097152),read(1048576,1048576))
                assertEquals("cache seek only requires a fresh identity probe",3,server.requestCount)
                assertTrue(disk.hitBytes.get()>=1048576)
                version.set("v2");val changed=ByteArray(fixture.size) { (fixture[it].toInt() xor 0x5a).toByte() };content.set(changed)
                assertArrayEquals(changed.copyOfRange(1048576,2097152),read(1048576,1048576))
                assertEquals("a changed identity must bypass old spans immediately",5,server.requestCount)
                assertEquals(0,status.nativeRangeRequests.get())
            } finally { disk.close();client.connectionPool.evictAll() }
        }
    }
    @Test fun lockedHoleBypassesImmediatelyAndOldGenerationsCannotCommitIntoNewSources()=withCache { handle ->
        val status=RangePlaybackStatus(8,32*1048576,2*1048576,24*1048576)
        val disk=DiskPrefetcher(handle,"versioned",status)
        try {
            val old=disk.validate(RangeIdentity(1048576,"\"v1\"",null))
            val bytes=ByteArray(1048576) { it.toByte() }
            val hole=handle.cache.startReadWrite("versioned",0,bytes.size.toLong())
            try {
                val before=System.nanoTime()
                assertFalse(old.contains(0,bytes.size));assertEquals(-1,old.read(0,ByteArray(1),0,1))
                assertTrue("a writer's hole cannot block playback",System.nanoTime()-before<100_000_000)
            } finally { handle.cache.releaseHoleSpan(hole) }
            assertTrue(old.persist(0,bytes))
            disk.seek(8192)
            assertFalse("seek invalidates outstanding storage leases",old.persist(0,bytes))
            val changed=disk.validate(RangeIdentity(1048576,"\"v2\"",null))
            val deadline=System.nanoTime()+5_000_000_000L
            while(!changed.enabled && System.nanoTime()<deadline) Thread.sleep(2)
            assertTrue(changed.enabled);assertFalse(changed.contains(0,bytes.size))
            val next=ByteArray(bytes.size) { (it xor 0x5a).toByte() }
            assertTrue(changed.persist(0,next));val actual=ByteArray(next.size)
            var offset=0
            while(offset<actual.size) { val n=changed.read(offset.toLong(),actual,offset,actual.size-offset);assertTrue(n>0);offset+=n }
            assertArrayEquals(next,actual)
            assertFalse(old.persist(0,bytes))
        } finally { disk.close() }
    }
    @Test fun overlappingSeekBodiesFillOnlyHolesAndNeverClaimAnIncompleteDiskCommit()=withCache { handle ->
        val disk=DiskPrefetcher(handle,"overlap",RangePlaybackStatus(8,32*1048576,2*1048576,24*1048576))
        try {
            val bytes=ByteArray(2*1048576) { (it%251).toByte() }
            val store=disk.validate(RangeIdentity(bytes.size.toLong(),"\"stable\"",null))
            assertTrue(store.persist(0,bytes.copyOfRange(0,65536)))
            assertTrue(store.persist(1048576,bytes.copyOfRange(1048576,1114112)))
            assertFalse(store.contains(0,bytes.size))
            assertTrue(store.persist(0,bytes));assertTrue(store.contains(0,bytes.size))
            val actual=ByteArray(bytes.size);var offset=0
            while(offset<actual.size) { val n=store.read(offset.toLong(),actual,offset,actual.size-offset);assertTrue(n>0);offset+=n }
            assertArrayEquals(bytes,actual)
            assertEquals("cached portions were not rewritten",bytes.size.toLong(),disk.writtenBytes.get())
            assertEquals(bytes.size.toLong(),handle.cache.cacheSpace)
        } finally { disk.close() }
    }
    @Test fun nonRangeServersFallBackToOneRealHttpBodyWithIdenticalBytes()=withCache { handle ->
        val bytes=ByteArray(1048576) { (it%251).toByte() }
        for((lanes,useDisk) in listOf(8 to false,8 to true,1 to true)) okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
                    assertEquals("/proxy/emby/original.mkv?sig=fixture",request.path)
                    assertEquals("fixture-auth",request.getHeader("X-Test-Auth"))
                    return okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes))
                }
            }
            val monitor=tv.ember.client.monitor.HttpTransportMonitor(clock={ System.nanoTime()/1_000_000 })
            val client=okhttp3.OkHttpClient.Builder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).eventListenerFactory(monitor).build()
            val headers=mapOf("X-Test-Auth" to "fixture-auth")
            val factory=androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client).setDefaultRequestProperties(headers)
            val status=RangePlaybackStatus(lanes,32*1048576,2*1048576,24*1048576)
            val disk=if(useDisk) DiskPrefetcher(handle,"fallback-$lanes",status) else null
            status.store=disk
            val url=server.url("/proxy/emby/original.mkv?sig=fixture").toString()
            val source=RangePlaybackDataSource(factory,client,url,headers,status)
            try {
                assertEquals(bytes.size.toLong(),source.open(androidx.media3.datasource.DataSpec.Builder().setUri(url).build()))
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
                while(true) { val n=source.read(buffer,0,buffer.size);if(n<0) break;out.write(buffer,0,n) }
                assertArrayEquals(bytes,out.toByteArray());assertTrue(status.rangeUnsupported)
                assertEquals("one rejected capability request, then a continuous fallback",2,server.requestCount)
                assertNull(status.reader);assertEquals(0,disk?.hitBytes?.get() ?: 0L)
                assertEquals(0,monitor.activeRangeRequests.get());assertTrue(monitor.peakConnections.get()<=1)
            } finally { source.close();disk?.close();client.connectionPool.evictAll() }
        }
    }
    @Test fun redirectedExtensionlessPlaylistsKeepResolvedSegmentBaseWithDiskEnabled()=withCache { handle ->
        for((path,mime) in listOf("/hls/master.m3u8" to "application/vnd.apple.mpegurl","/dash/master.mpd" to "application/dash+xml")) {
            okhttp3.mockwebserver.MockWebServer().use { origin -> okhttp3.mockwebserver.MockWebServer().use { cdn ->
                val manifest=if(path.endsWith("m3u8")) "#EXTM3U\n#EXTINF:4,\nsegment.ts\n" else "<MPD><BaseURL>segments/</BaseURL></MPD>"
                val bytes=manifest.toByteArray();val rangeRequests=java.util.concurrent.atomic.AtomicInteger()
                cdn.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
                        assertEquals("$path?sig=fixture",request.path);assertNull(request.getHeader("X-Emby-Token"))
                        val response=okhttp3.mockwebserver.MockResponse().setHeader("Content-Type",mime).setBody(manifest)
                        if(request.getHeader("Range")!=null) {
                            rangeRequests.incrementAndGet();response.setResponseCode(206).setHeader("Content-Range","bytes 0-${bytes.lastIndex}/${bytes.size}")
                        }
                        return response
                    }
                }
                origin.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
                        assertEquals("server-token",request.getHeader("X-Emby-Token"))
                        return okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location",cdn.url("$path?sig=fixture"))
                    }
                }
                val client=HttpClient.playback.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
                val headers=mapOf("X-Emby-Token" to "server-token")
                val factory=androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client).setDefaultRequestProperties(headers)
                val status=RangePlaybackStatus(8,32*1048576,2*1048576,24*1048576)
                val disk=DiskPrefetcher(handle,"manifest-$path",status);status.store=disk
                val url=origin.url("/proxy/emby/stream?sig=fixture").toString()
                val source=DiskPlaybackDataSource(url,{ RangePlaybackDataSource(factory,client,url,headers,status) },disk)
                try {
                    assertEquals(bytes.size.toLong(),source.open(androidx.media3.datasource.DataSpec.Builder().setUri(url).build()))
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
                    while(true) { val n=source.read(buffer,0,buffer.size);if(n<0) break;out.write(buffer,0,n) }
                    assertArrayEquals(bytes,out.toByteArray());assertEquals(cdn.url("$path?sig=fixture").toString(),source.uri.toString())
                    assertEquals("manifest detection must not start a parallel worker wave",1,rangeRequests.get())
                    assertEquals(0,disk.writtenBytes.get())
                } finally { source.close();disk.close();client.connectionPool.evictAll() }
            } }
        }
    }
    @Test fun lowDiskSpaceStopsWritesButDoesNotDiscardAlreadyVerifiedCacheHits()=withCache { handle ->
        val status=RangePlaybackStatus(8,32*1048576,2*1048576,24*1048576)
        val identity=RangeIdentity(2L*1048576,"\"stable\"",null)
        val bytes=ByteArray(1048576) { (it%251).toByte() }
        DiskPrefetcher(handle,"low-space",status).use { disk -> assertTrue(disk.validate(identity).persist(0,bytes)) }
        val fullDisk=object:java.io.File(handle.directory.path) { override fun getUsableSpace()=0L }
        DiskPrefetcher(handle.copy(directory=fullDisk),"low-space",status).use { disk ->
            val store=disk.validate(identity)
            assertFalse(store.persist(1048576,bytes));assertFalse(store.canPrefetch)
            assertTrue(store.enabled);assertTrue(store.contains(0,bytes.size))
            val actual=ByteArray(bytes.size);assertEquals(bytes.size,store.read(0,actual,0,actual.size))
            assertArrayEquals(bytes,actual);store.offer(1048576,bytes)
            assertEquals(0,disk.pendingWriteBytes);assertEquals(bytes.size.toLong(),handle.cache.cacheSpace)
        }
    }
    @Test fun invalid206CannotBypassOffsetValidationThroughNativeFallback() {
        for(lanes in listOf(1,8)) for(bad in listOf("offset","missing","total")) okhttp3.mockwebserver.MockWebServer().use { server ->
            val bytes=ByteArray(1048576) { (it%251).toByte() }
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
                    val response=okhttp3.mockwebserver.MockResponse().setResponseCode(206).setBody(okio.Buffer().write(bytes,0,65536))
                    when(bad) {
                        "offset" -> response.setHeader("Content-Range","bytes 0-65535/${bytes.size}")
                        "total" -> response.setHeader("Content-Range","bytes 16384-81919/81919")
                    }
                    return response
                }
            }
            val client=okhttp3.OkHttpClient.Builder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
            val url=server.url("/original.mkv").toString()
            val source=RangePlaybackDataSource(androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client),client,url,emptyMap(),
                RangePlaybackStatus(lanes,32*1048576,2*1048576,24*1048576))
            try {
                try {
                    source.open(androidx.media3.datasource.DataSpec.Builder().setUri(url).setPosition(16384).setLength(65536).build())
                    fail("malformed 206 reached the extractor via native fallback")
                } catch(e:java.io.IOException) { assertTrue(e.message.orEmpty().contains("range",true)) }
            } finally { source.close();client.connectionPool.evictAll() }
        }
    }
}
