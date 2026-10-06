package tv.ember.client

import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.mockwebserver.Dispatcher
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.network.*
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class RangeWindowTest {
    private val source=ByteArray(20*1048576) { ((it*31+it/7919)%251).toByte() }
    private val chunk=256*1024
    private fun reply(request:RecordedRequest):MockResponse {
        val parts=request.getHeader("Range")!!.removePrefix("bytes=").split('-')
        val start=parts[0].toInt();val end=minOf(parts[1].toInt(),source.lastIndex)
        return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${source.size}")
            .setHeader("ETag","\"stable\"").setHeader("Last-Modified","Mon, 05 Oct 2026 00:00:00 GMT")
            .setBody(Buffer().write(source,start,end-start+1))
    }
    private fun client()=OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1)).build()
    private fun await(check:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(System.nanoTime()<deadline) { if(check()) return;Thread.sleep(5) };fail("pipeline did not progress")
    }
    private fun digest(reader:ParallelRangeReader):ByteArray {
        val hash=MessageDigest.getInstance("SHA-256");val buf=ByteArray(65536)
        while(true) { val n=reader.read(buf,0,buf.size);if(n<0) break;hash.update(buf,0,n) };return hash.digest()
    }
    @Test fun workersRefillBeforeConsumptionAndSlowHeadDoesNotParkOtherWorkers() {
        for(lanes in listOf(2,4,8)) MockWebServer().use { server ->
            val offsets=ConcurrentHashMap.newKeySet<Long>();val duplicates=AtomicInteger()
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                val offset=request.getHeader("Range")!!.removePrefix("bytes=").substringBefore('-').toLong()
                if(!offsets.add(offset)) duplicates.incrementAndGet()
                return reply(request).apply {
                    if(offset==0L) setBodyDelay(800,TimeUnit.MILLISECONDS)
                    else if(offset/chunk%7==3L) setBodyDelay(40,TimeUnit.MILLISECONDS)
                }
            } }
            val http=client()
            ParallelRangeReader(http,server.url("/original.mkv?sig=test").toString(),emptyMap(),0,-1,lanes,chunk,lanes*chunk*4) {}.use { reader ->
                val before=System.nanoTime();reader.open()
                assertTrue("open must wait only for headers, not the slow body",System.nanoTime()-before<TimeUnit.MILLISECONDS.toNanos(500))
                await { offsets.size>=lanes*2 }
                assertTrue("no bytes consumed, yet workers took next jobs",reader.snapshot().completedWaitingChunks>=lanes)
                assertTrue(reader.bufferedBytes.get()<=lanes.toLong()*chunk*4)
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source),digest(reader))
                assertEquals(0,duplicates.get())
            }
            http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()
        }
    }
    @Test fun loadControlPauseDrainsRequestsAndResumeRefillsWithoutReopening() {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest)=reply(request).setHeadersDelay(30,TimeUnit.MILLISECONDS) }
            ParallelRangeReader(client(),server.url("/file").toString(),emptyMap(),0,-1,4,chunk,4*chunk*4) {}.use { reader ->
                reader.open();reader.setLoadingDemand(false)
                await { reader.activeRequests.get()==0 }
                val count=server.requestCount;Thread.sleep(100);assertEquals(count,server.requestCount)
                assertFalse(reader.snapshot().loadingDemand)
                reader.setLoadingDemand(true);await { server.requestCount>count }
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source),digest(reader))
            }
        }
    }
    @Test fun seekClosesOldHttpBodiesAndNewUnalignedPositionRemainsExact() {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest)=reply(request).throttleBody(8192,10,TimeUnit.MILLISECONDS) }
            val http=client();val old=ParallelRangeReader(http,server.url("/file").toString(),emptyMap(),0,-1,8,chunk) {}
            old.open();await { old.activeRequests.get()>=6 };old.close();await { old.activeRequests.get()==0 }
            assertTrue(old.cancelledForeground.get()>=6)
            val start=7*1048576+19L;val length=2*1048576+117L
            ParallelRangeReader(http,server.url("/file").toString(),emptyMap(),start,length,8,chunk) {}.use { reader ->
                assertEquals(length,reader.open());assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source.copyOfRange(start.toInt(),(start+length).toInt())),digest(reader))
            }
        }
    }
    @Test fun lastModifiedChangesAndMissingValidatorsAreNotMerged() {
        for(change in listOf("date","missing","total")) MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                val response=reply(request)
                if(!request.getHeader("Range")!!.startsWith("bytes=0-")) when(change) {
                    "date" -> response.setHeader("Last-Modified","Tue, 06 Oct 2026 00:00:00 GMT")
                    "missing" -> response.removeHeader("ETag")
                    "total" -> response.setHeader("Content-Range",response.headers["Content-Range"]!!.substringBeforeLast('/')+"/${source.size+1}")
                }
                return response
            } }
            ParallelRangeReader(client(),server.url("/file").toString(),emptyMap(),0,-1,4,chunk) {}.use { reader ->
                try { reader.open();digest(reader);fail("changed identity was merged") } catch(e:java.io.IOException) { assertTrue(e.message.orEmpty().contains("version changed")) }
            }
        }
    }
    @Test(timeout=30000) fun interruptedBodiesResumeOnlyTheirMissingSuffixThroughTheSameScheduler() {
        for(diskEnabled in listOf(false,true)) MockWebServer().use { server ->
            val interrupted=java.util.concurrent.atomic.AtomicBoolean()
            val starts=java.util.concurrent.CopyOnWriteArrayList<Long>()
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val offset=request.getHeader("Range")!!.removePrefix("bytes=").substringBefore('-').toLong()
                    starts.add(offset)
                    return reply(request).apply {
                        if(offset==256*1024L && interrupted.compareAndSet(false,true)) setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    }
                }
            }
            val http=client();val store=if(diskEnabled) FileRangeStore(chunk,8L*1048576) else null
            try {
                ParallelRangeReader(http,server.url("/file").toString(),emptyMap(),0,-1,4,chunk,4*1048576,store) {}.use { reader ->
                    reader.open();assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source),digest(reader))
                    assertEquals(0,reader.duplicatedRangeRequests.get())
                    assertEquals(0,reader.cancelledForeground.get());assertEquals(0,reader.cancelledBackground.get())
                }
                assertTrue(interrupted.get())
                assertEquals("the completed prefix must not be requested again",1,starts.count { it==256*1024L })
                assertTrue("partial body retry owns only the missing suffix",starts.any { it in (256*1024L+1) until 512*1024L })
            } finally { store?.close();http.connectionPool.evictAll();http.dispatcher.executorService.shutdown() }
        }
    }
    @Test(timeout=30000) fun slowerNetworkTemporary503AndRecoveryKeepOtherLanesAliveAndBytesExact() {
        MockWebServer().use { server ->
            val failures=java.util.concurrent.atomic.AtomicInteger()
            val peak=java.util.concurrent.atomic.AtomicInteger()
            val live=java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val offset=request.getHeader("Range")!!.removePrefix("bytes=").substringBefore('-').toLong()
                    if(offset==256*1024L && failures.incrementAndGet()<=2) return MockResponse().setResponseCode(503)
                    return reply(request).apply { if(offset in (2*1048576L)..(6*1048576L)) throttleBody(32768,20,TimeUnit.MILLISECONDS) }
                }
            }
            val http=client().newBuilder().eventListener(object:EventListener() {
                override fun connectionAcquired(call:Call,connection:Connection) { val count=live.incrementAndGet();peak.updateAndGet { maxOf(it,count) } }
                override fun connectionReleased(call:Call,connection:Connection) { live.decrementAndGet() }
            }).build()
            FileRangeStore(chunk,8L*1048576).use { store ->
                ParallelRangeReader(http,server.url("/file").toString(),emptyMap(),0,-1,4,chunk,4*1048576,store) {}.use { reader ->
                    reader.open();assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source),digest(reader))
                    assertEquals(0,reader.cancelledForeground.get());assertEquals(0,reader.cancelledBackground.get())
                    assertEquals(0,reader.duplicatedRangeRequests.get());assertTrue(reader.bufferedBytes.get()<=4*1048576)
                }
            }
            assertEquals(3,failures.get());assertTrue(peak.get() in 2..4)
            http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()
        }
    }
    @Test(timeout=30000) fun loadingDemandNeverTurnsOffDiskPrefetchAndCachedSeekDoesNotUseNetwork() {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest)=reply(request).setHeadersDelay(10,TimeUnit.MILLISECONDS) }
            val http=client()
            FileRangeStore(chunk,12L*1048576).use { store ->
                val first=ParallelRangeReader(http,server.url("/file").toString(),emptyMap(),0,-1,8,chunk,4*1048576,store) {}
                first.open();first.setLoadingDemand(true)
                await { store.contains(0,12*1048576) }
                assertTrue(first.backgroundBytes.get()>0)
                assertTrue(first.snapshot().diskHighWatermark>=12L*1048576)
                first.close();await { first.activeRequests.get()==0 }
                val count=server.requestCount;val start=3L*1048576+17
                ParallelRangeReader(http,server.url("/file").toString(),emptyMap(),start,2L*1048576,8,chunk,4*1048576,store) {}.use { reader ->
                    reader.open();assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source.copyOfRange(start.toInt(),(start+2*1048576).toInt())),digest(reader))
                }
                assertEquals("cached seek is disk -> Media3 with no network probe",count,server.requestCount)
                val newStart=15L*1048576+19
                ParallelRangeReader(http,server.url("/file").toString(),emptyMap(),newStart,-1,8,chunk,4*1048576,store) {}.use { reader ->
                    reader.open();reader.setPlaybackPosition(newStart);reader.setLoadingDemand(false)
                    await { store.contains(newStart,(source.size-newStart).toInt()) }
                    assertTrue(reader.snapshot().diskHighWatermark>=source.size)
                    assertEquals(0,reader.snapshot().foregroundConnections)
                }
            }
            http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()
        }
    }

}
