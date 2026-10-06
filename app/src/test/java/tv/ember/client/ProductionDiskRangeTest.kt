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
    private fun withCache(requestedMb:Int=512,test:(PlaybackDiskCache.Handle)->Unit) {
        val folder=Files.createTempDirectory("bronya-simple-cache-").toFile()
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext():Context=this
            override fun getCacheDir()=folder
        }
        val handle=requireNotNull(PlaybackDiskCache(context).configure(requestedMb,80_000_000,15))
        try { test(handle) } finally { handle.cache.release();folder.deleteRecursively() }
    }
    @Test fun productionDiskCacheKeepsFiftyEightyAndHundredMbpsConsumersFed() {
        for(mbps in listOf(20,50,80,100)) withCache { handle ->
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
    @Test(timeout=240000) fun fiftyMbpsWith128MbpsNetworkContinuouslyBuildsOneGiBAheadWithBoundedRam()=withCache(1024) { continuousDiskCase(it,50,false) }
    @Test(timeout=180000) fun allBitratesSurviveNetworkSlowdownInterruptionAndRecoveryWithShortRam() {
        for(mbps in listOf(20,50,80,100)) withCache(1024) { continuousDiskCase(it,mbps,true) }
    }
    private fun continuousDiskCase(handle:PlaybackDiskCache.Handle,mbps:Int,disturbed:Boolean) {
        val mib=1048576L
        val sourceSize=3L*1024*mib
        val startedAt=java.util.concurrent.atomic.AtomicLong()
        val networkFailures=java.util.concurrent.atomic.AtomicInteger()
        val bodyInterrupted=java.util.concurrent.atomic.AtomicBoolean()
        val received=java.util.concurrent.atomic.AtomicLong()
        val overlap=java.util.concurrent.atomic.AtomicInteger()
        val requested=java.util.TreeMap<Long,Long>()
        val connections=java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Connection>()
        val acquired=java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Connection>()
        val downloadSamples=java.util.concurrent.CopyOnWriteArrayList<Long>()
        val prefetchSamples=java.util.concurrent.CopyOnWriteArrayList<Int>()
        val ramSamples=java.util.concurrent.CopyOnWriteArrayList<Int>()
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
                    val elapsed=startedAt.get().takeIf { it>0 }?.let { (System.nanoTime()-it)/1e9 } ?: 0.0
                    if(disturbed && elapsed in 4.0..5.0) {
                        networkFailures.incrementAndGet()
                        return okhttp3.mockwebserver.MockResponse().setResponseCode(503)
                    }
                    val parts=request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                    val start=parts[0].toLong();val end=parts[1].toLong()
                    synchronized(requested) {
                        if(requested.floorEntry(start)?.value?.let { it>=start }==true || requested.ceilingKey(start)?.let { it<=end }==true) overlap.incrementAndGet()
                        requested[start]=end
                    }
                    val bytes=ByteArray((end-start+1).toInt()) { ((start+it)%251).toByte() }
                    // Eight persistent HTTP/1.1 lanes offer 128 Mbps in total, including a 20 ms RTT.
                    return okhttp3.mockwebserver.MockResponse().setResponseCode(206)
                        .setHeader("Content-Range","bytes $start-$end/$sourceSize").setHeader("ETag","\"soak\"")
                        .setHeadersDelay(20,java.util.concurrent.TimeUnit.MILLISECONDS)
                        .setBody(okio.Buffer().write(bytes)).throttleBody(65536,if(disturbed && elapsed in 2.0..4.0) (65536*8L*8/ (mbps*0.6)).toLong() else if(disturbed) 16384 else 32768,java.util.concurrent.TimeUnit.MICROSECONDS)
                        .apply { if(disturbed && elapsed in 6.0..7.0 && bodyInterrupted.compareAndSet(false,true)) setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
                }
            }
            val client=okhttp3.OkHttpClient.Builder().protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .connectionPool(okhttp3.ConnectionPool(8,30,java.util.concurrent.TimeUnit.SECONDS))
                .eventListener(object:okhttp3.EventListener() {
                    override fun connectionAcquired(call:okhttp3.Call,connection:okhttp3.Connection) { connections.add(connection);acquired.add(connection) }
                    override fun connectionReleased(call:okhttp3.Call,connection:okhttp3.Connection) { connections.remove(connection) }
                }).build()
            val plan=StreamPolicy.create(8,mbps*1_000_000L,512*mib,64*mib,false,true)
            val status=RangePlaybackStatus(8,plan.budgetBytes,plan.chunkBytes,plan.aheadWindowBytes)
            val disk=DiskPrefetcher(handle,"soak",status)
            val loader=java.util.concurrent.Executors.newSingleThreadExecutor()
            val timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
            val queue=java.util.concurrent.ArrayBlockingQueue<androidx.media3.exoplayer.upstream.Allocation>(1024)
            val bytesPlayed=java.util.concurrent.atomic.AtomicLong()
            val timeline=androidx.media3.exoplayer.source.SinglePeriodTimeline(600_000_000,true,false,false,null,androidx.media3.common.MediaItem.Builder().build())
            val playerId=androidx.media3.exoplayer.analytics.PlayerId.UNSET
            val loadControl=tv.ember.client.player.TvLoadControl(tv.ember.client.player.BufferPolicy(45000,120000,5000,5000,64*mib.toInt()),status::setLoadingDemand)
            loadControl.onPrepared(playerId)
            val allocator=loadControl.getAllocator(playerId)
            val reader=ParallelRangeReader(client,server.url("/original.mkv").toString(),emptyMap(),0,-1,8,plan.chunkBytes,plan.aheadWindowBytes,disk) { received.addAndGet(it.toLong()) }
            status.reader=reader
            var zeroReads=0;var minimumRam=Long.MAX_VALUE;var peakRam=0L
            try {
                reader.open();reader.setPlaybackPosition(0)
                loader.submit {
                    try {
                        var mediaPosition=0L
                        while(!Thread.currentThread().isInterrupted) {
                            val parameters=androidx.media3.exoplayer.LoadControl.Parameters(playerId,timeline,
                                androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
                                bytesPlayed.get()*8/mbps,queue.size*65536L*8/mbps,1f,true,false,androidx.media3.common.C.TIME_UNSET,androidx.media3.common.C.TIME_UNSET)
                            if(!loadControl.shouldContinueLoading(parameters)) { Thread.sleep(2);continue }
                            val allocation=allocator.allocate();var count=0
                            while(count<65536) { val n=reader.read(allocation.data,allocation.offset+count,65536-count);if(n<0) break;count+=n }
                            if(count!=65536) { allocator.release(allocation);break }
                            queue.put(allocation);mediaPosition+=count
                            disk.advance(mediaPosition);disk.updatePlaybackBuffer(queue.size*65536L)
                        }
                    } catch(e:Throwable) { if(!Thread.currentThread().isInterrupted) failure.set(e) }
                }
                val primeDeadline=System.nanoTime()+20_000_000_000L
                while(queue.size<512 && failure.get()==null && System.nanoTime()<primeDeadline) Thread.sleep(10)
                failure.get()?.let { throw it };assertTrue("32 MiB playable startup",queue.size>=512)
                var lastBytes=received.get()
                timer.scheduleAtFixedRate({
                    val count=received.get();val sample=reader.snapshot()
                    if(disk.cachedAheadBytes<disk.aheadBytes-8*mib) {
                        downloadSamples.add(count-lastBytes);prefetchSamples.add(sample.prefetchConnections)
                    }
                    ramSamples.add(queue.size);lastBytes=count
                },1,1,java.util.concurrent.TimeUnit.SECONDS)
                val begin=System.nanoTime();startedAt.set(begin);var due=begin
                if(disturbed) {
                    // Interrupt sockets already carrying slow bodies; do not wait for their
                    // next request to observe the outage and subsequent recovery.
                    timer.schedule({ connections.toList().forEach { runCatching { it.socket().close() } } },4,java.util.concurrent.TimeUnit.SECONDS)
                    timer.schedule({ connections.firstOrNull()?.let { bodyInterrupted.set(true);runCatching { it.socket().close() } } },6,java.util.concurrent.TimeUnit.SECONDS)
                }
                var reachedAt=0L
                while(System.nanoTime()-begin<180_000_000_000L) {
                    failure.get()?.let { throw it }
                    val allocation=queue.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS)
                    if(allocation==null) { zeroReads++;continue }
                    val position=bytesPlayed.get()
                    assertEquals((position%251).toByte(),allocation.data[allocation.offset])
                    assertEquals(((position+65535)%251).toByte(),allocation.data[allocation.offset+65535])
                    bytesPlayed.addAndGet(65536);allocator.release(allocation)
                    disk.updatePlaybackBuffer(queue.size*65536L)
                    val ram=queue.size*65536L;minimumRam=minOf(minimumRam,ram);peakRam=maxOf(peakRam,ram+reader.bufferedBytes.get())
                    if(disturbed && System.nanoTime()-begin>=12_000_000_000L) break
                    if(!disturbed && disk.cachedAheadBytes>=disk.aheadBytes-4*mib) {
                        if(reachedAt==0L) reachedAt=System.nanoTime()
                        if(System.nanoTime()-reachedAt>=5_000_000_000L) break
                    }
                    due+=65536L*8000/mbps
                    val delay=due-System.nanoTime();if(delay>0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay)
                }
                val elapsed=(System.nanoTime()-begin)/1e9
                val result="$mbps Mbps / ${if(disturbed) 256 else 128} Mbps (faults=$disturbed): diskAhead=${disk.cachedAheadBytes/mib} MiB, target=${disk.aheadBytes/mib} MiB, RAM minimum=${minimumRam/mib} MiB, combined peak=${peakRam/mib} MiB, underruns=$zeroReads, averageDownload=${downloadSamples.average()*8/1e6} Mbps, zeroDownloadSeconds=${downloadSamples.count { it==0L }}, averagePrefetch=${prefetchSamples.average()}, uniqueTCP=${acquired.size}, resumedHeaders=${if(disturbed) overlap.get() else 0}, duplicateRanges=${reader.duplicatedRangeRequests.get()}, elapsed=${elapsed}s"
                println("CONTINUOUS_DISK_RESULT $result")
                assertTrue(result,disk.cachedAheadBytes>=(if(disturbed) 32 else 1020)*mib)
                assertEquals(result,0,zeroReads);assertTrue(result,minimumRam>=(if(disturbed) 16 else 24)*mib)
                assertTrue(result,peakRam<=96*mib+65536)
                assertTrue(result,downloadSamples.size>=if(disturbed) 10 else 30)
                if(!disturbed) assertEquals(result,0,downloadSamples.count { it==0L })
                else { assertTrue("outage was exercised: $result",networkFailures.get()>0);assertTrue("body interruption was exercised: $result",bodyInterrupted.get());assertTrue("download recovered: $result",downloadSamples.takeLast(2).average()>8*mib) }
                assertTrue(result,prefetchSamples.average()>=1)
                if(!disturbed) assertEquals(result,0,overlap.get());assertEquals(0,reader.duplicatedRangeRequests.get())
                assertTrue("persistent sockets should be reused: $result",acquired.size<=if(disturbed) 20 else 8)
                if(disturbed) {
                    loader.shutdownNow();reader.close();loader.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)
                    val closeDeadline=System.nanoTime()+3_000_000_000L
                    while(reader.activeRequests.get()>0 && System.nanoTime()<closeDeadline) Thread.sleep(2)
                    assertEquals("seek releases obsolete lanes",0,reader.activeRequests.get())
                    val seekPosition=bytesPlayed.get()+16*mib+19
                    disk.seek(seekPosition)
                    val count=server.requestCount
                    ParallelRangeReader(client,server.url("/original.mkv").toString(),emptyMap(),seekPosition,2*mib,8,
                        plan.chunkBytes,plan.aheadWindowBytes,disk) {}.use { seekReader ->
                        assertEquals(2*mib,seekReader.open())
                        val actual=ByteArray((2*mib).toInt());var offset=0
                        while(offset<actual.size) { val n=seekReader.read(actual,offset,actual.size-offset);assertTrue(n>0);offset+=n }
                        assertArrayEquals(ByteArray(actual.size) { ((seekPosition+it)%251).toByte() },actual)
                    }
                    assertEquals("$mbps Mbps cached seek must not redownload",count,server.requestCount)
                    val uncached=2L*1024*mib+37
                    disk.seek(uncached)
                    ParallelRangeReader(client,server.url("/original.mkv").toString(),emptyMap(),uncached,mib,8,
                        plan.chunkBytes,plan.aheadWindowBytes,disk) {}.use { seekReader ->
                        assertEquals(mib,seekReader.open())
                        val actual=ByteArray(mib.toInt());var offset=0
                        while(offset<actual.size) { val n=seekReader.read(actual,offset,actual.size-offset);assertTrue(n>0);offset+=n }
                        assertArrayEquals(ByteArray(actual.size) { ((uncached+it)%251).toByte() },actual)
                        assertEquals(0,seekReader.duplicatedRangeRequests.get())
                    }
                    assertTrue("uncached seek starts a new forward window",server.requestCount>count)
                }
            } finally {
                timer.shutdownNow();reader.close();loader.shutdownNow();loader.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)
                disk.close();loadControl.onReleased(playerId);client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test(timeout=20000) fun selectedSingleConnectionUsesTheSharedSchedulerAndPrefetchesWhileRamLoadingIsPaused()=withCache { handle ->
        val fixture=ByteArray(16*1048576) { (it%251).toByte() }
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
                    val parts=request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                    val start=parts[0].toInt();val end=parts[1].toInt()
                    return okhttp3.mockwebserver.MockResponse().setResponseCode(206).setHeader("ETag","\"single-shared\"")
                        .setHeader("Content-Range","bytes $start-$end/${fixture.size}")
                        .setBody(okio.Buffer().write(fixture,start,end-start+1))
                }
            }
            val client=okhttp3.OkHttpClient.Builder().build()
            val url=server.url("/original.mkv").toString()
            val status=RangePlaybackStatus(1,8*1048576,2*1048576,8*1048576)
            val disk=DiskPrefetcher(handle,"single-shared",status);status.store=disk
            val source=DiskPlaybackDataSource(url,{ RangePlaybackDataSource(androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client),client,url,emptyMap(),status) },disk)
            try {
                source.open(androidx.media3.datasource.DataSpec.Builder().setUri(url).build())
                assertNotNull("selected single mode still uses the unified owner",status.reader)
                status.setLoadingDemand(false)
                val deadline=System.nanoTime()+5_000_000_000L
                while(!disk.contains(0,fixture.size) && System.nanoTime()<deadline) Thread.sleep(2)
                assertTrue("disk is independent of paused RAM demand",disk.contains(0,fixture.size))
                assertTrue(status.reader!!.backgroundBytes.get()>0)
                assertTrue(status.bufferedBytes<=8*1048576)
                val requests=server.requestCount;val actual=ByteArray(fixture.size);var offset=0
                while(offset<actual.size) { val n=source.read(actual,offset,actual.size-offset);assertTrue(n>0);offset+=n }
                assertArrayEquals(fixture,actual);assertEquals(requests,server.requestCount)
                assertEquals(0,status.reader!!.duplicatedRangeRequests.get())
            } finally { source.close();disk.close();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
        }
    }

}
