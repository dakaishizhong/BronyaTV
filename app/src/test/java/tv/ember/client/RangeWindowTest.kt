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
}
