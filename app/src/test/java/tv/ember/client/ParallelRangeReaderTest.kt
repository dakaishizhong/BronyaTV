package tv.ember.client

import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.mockwebserver.Dispatcher
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.network.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ParallelRangeReaderTest {
    private val bytes=ByteArray(4*1024*1024) { ((it*31+it/7919)%251).toByte() }
    private val chunk=256*1024
    private fun response(request:RecordedRequest,slow:Boolean=false,etag:String="\"fixture-v1\""):MockResponse {
        assertEquals("identity",request.getHeader("Accept-Encoding"))
        assertEquals("signed-value",request.getHeader("X-Test-Signature"))
        val parts=request.getHeader("Range")!!.removePrefix("bytes=").split('-')
        val start=parts[0].toInt();val end=minOf(parts[1].toInt(),bytes.lastIndex)
        return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${bytes.size}")
            .setHeader("ETag",etag).setBody(Buffer().write(bytes,start,end-start+1))
            .apply { if(slow) throttleBody(65536,25,TimeUnit.MILLISECONDS) }
    }
    private fun client(listener:EventListener?=null)=OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1))
        .apply { if(listener!=null) eventListener(listener) }.build()
    private fun readAll(reader:ParallelRangeReader):ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(7919)
        while(true) { val n=reader.read(buffer,0,buffer.size);if(n<0) break;out.write(buffer,0,n) }
        return out.toByteArray()
    }
    @Test fun nonAlignedStartAndLengthRemainExactWhenRangesCompleteOutOfOrder() {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                val r=response(request)
                val offset=request.getHeader("Range")!!.removePrefix("bytes=").substringBefore('-').toInt()
                if(offset==12345+65536) r.setBodyDelay(120,TimeUnit.MILLISECONDS)
                return r
            } }
            val peak=AtomicLong();var reader:ParallelRangeReader?=null
            val count=2*1024*1024+765
            reader=ParallelRangeReader(client(),server.url("/original.mkv").toString(),mapOf("X-Test-Signature" to "signed-value"),12345,count.toLong(),4,chunk) {
                peak.updateAndGet { maxOf(it,reader!!.bufferedBytes.get()) }
            }
            reader.use { assertEquals(count.toLong(),it.open());assertArrayEquals(bytes.copyOfRange(12345,12345+count),readAll(it)) }
            assertEquals("bytes=12345-77880",server.takeRequest().getHeader("Range"))
            assertTrue("bounded prefetch",peak.get()<=5L*chunk)
            assertEquals(0L,reader.bufferedBytes.get())
        }
    }
    @Test fun independentConnectionsImprovePerConnectionThrottledTransferWithoutChangingBytes() {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest)=response(request,true) }
            val active=AtomicInteger();val peak=AtomicInteger()
            val http=client(object:EventListener() {
                override fun connectionAcquired(call:Call,connection:Connection) { peak.updateAndGet { maxOf(it,active.incrementAndGet()) } }
                override fun connectionReleased(call:Call,connection:Connection) { active.decrementAndGet() }
            })
            val url=server.url("/original.mp4").toString()
            val sequential=ByteArrayOutputStream();val start=System.nanoTime()
            for(offset in bytes.indices step chunk) {
                http.newCall(Request.Builder().url(url).header("X-Test-Signature","signed-value").header("Accept-Encoding","identity")
                    .header("Range","bytes=$offset-${offset+chunk-1}").build()).execute().use { sequential.write(it.body!!.bytes()) }
            }
            val sequentialMs=(System.nanoTime()-start)/1_000_000
            val next=System.nanoTime()
            ParallelRangeReader(http,url,mapOf("X-Test-Signature" to "signed-value"),0,-1,4,chunk) {}.use {
                assertEquals(bytes.size.toLong(),it.open());assertArrayEquals(sequential.toByteArray(),readAll(it))
            }
            val parallelMs=(System.nanoTime()-next)/1_000_000
            println("Throttled fixture: single=$sequentialMs ms, four=$parallelMs ms, peak TCP connections=${peak.get()}")
            assertTrue("actual independent TCP overlap",peak.get()>=3)
            assertTrue("four connections should exceed 1.7x on this per-connection fixture ($sequentialMs / $parallelMs)",sequentialMs>parallelMs*1.7)
        }
    }
    @Test fun redirectedSignedRangesPreserveProviderQueryAndNeverForwardEmbyCredentials() {
        MockWebServer().use { origin -> MockWebServer().use { cdn ->
            cdn.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                assertEquals("/original.mkv?sig=signed-query",request.path)
                assertNull(request.getHeader("X-Emby-Token"));assertNull(request.getHeader("X-Emby-Authorization"))
                return response(request)
            } }
            origin.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                assertEquals("server-token",request.getHeader("X-Emby-Token"))
                return MockResponse().setResponseCode(302).setHeader("Location",cdn.url("/original.mkv?sig=signed-query"))
            } }
            val http=tv.ember.client.network.HttpClient.playback.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
            ParallelRangeReader(http,origin.url("/original").toString(),mapOf("X-Test-Signature" to "signed-value",
                "X-Emby-Token" to "server-token","X-Emby-Authorization" to "server-auth"),0,1048576,4,chunk) {}.use {
                assertEquals(1048576,it.open());assertArrayEquals(bytes.copyOfRange(0,1048576),readAll(it))
            }
        } }
    }
    @Test fun exactEndOfFile416BecomesAnEmptyReadButBeyondEndStillFails() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range","bytes */12345")) }
            ParallelRangeReader(client(),server.url("/file").toString(),emptyMap(),12345,-1,4,chunk) { fail("EOF cannot transfer bytes") }.use {
                assertEquals(0L,it.open());assertEquals(12345L,it.totalBytes);assertEquals(-1,it.read(ByteArray(1),0,1));assertEquals(0L,it.bufferedBytes.get())
            }
            ParallelRangeReader(client(),server.url("/file").toString(),emptyMap(),12346,-1,4,chunk) {}.use {
                try { it.open();fail("Beyond EOF must still fail") } catch(e:RangeHttpException) { assertEquals(416,e.code) }
            }
        }
    }
    @Test fun firstHttp200RequestsSingleConnectionFallback() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("whole file"))
            ParallelRangeReader(client(),server.url("/file").toString(),emptyMap(),0,-1,4,chunk) {}.use {
                try { it.open();fail("must reject 200 as a range") } catch(e:RangeUnavailableException) { assertTrue(e.message!!.contains("one connection")) }
            }
        }
    }
    @Test fun laterHttp410RemainsAvailableForPlaybackUrlRefresh() {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest)=
                if(request.getHeader("Range")!!.startsWith("bytes=0-")) response(request) else MockResponse().setResponseCode(410) }
            ParallelRangeReader(client(),server.url("/file").toString(),mapOf("X-Test-Signature" to "signed-value"),0,-1,4,chunk) {}.use {
                it.open();try { readAll(it);fail("410 must propagate") } catch(e:RangeHttpException) { assertEquals(410,e.code) }
            }
        }
    }
    @Test fun changedFileValidatorStopsMerging() {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest)=response(request,
                etag=if(request.getHeader("Range")!!.startsWith("bytes=0-")) "\"v1\"" else "\"v2\"") }
            ParallelRangeReader(client(),server.url("/file").toString(),mapOf("X-Test-Signature" to "signed-value"),0,-1,4,chunk) {}.use {
                it.open();try { readAll(it);fail("must reject changed file") } catch(e:IOException) { assertTrue(e.message!!.contains("version changed")) }
            }
        }
    }
    @Test fun incorrectRangeOffsetStopsMerging() {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                val r=response(request)
                if(!request.getHeader("Range")!!.startsWith("bytes=0-")) r.setHeader("Content-Range","bytes 0-${chunk-1}/${bytes.size}")
                return r
            } }
            ParallelRangeReader(client(),server.url("/file").toString(),mapOf("X-Test-Signature" to "signed-value"),0,-1,4,chunk) {}.use {
                it.open();try { readAll(it);fail("must reject wrong position") } catch(e:IOException) { assertTrue(e.message!!.contains("position mismatch")) }
            }
        }
    }
    @Test fun closingWhileAReadWaitsCancelsWorkersPromptly() {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                val r=response(request)
                if(!request.getHeader("Range")!!.startsWith("bytes=0-")) r.throttleBody(1024,1,TimeUnit.SECONDS)
                return r
            } }
            val reader=ParallelRangeReader(client(),server.url("/file").toString(),mapOf("X-Test-Signature" to "signed-value"),0,-1,4,chunk) {}
            val executor=Executors.newSingleThreadExecutor()
            try {
                reader.open();val waiting=executor.submit<Boolean> { try { readAll(reader);false } catch(e:IOException) { true } }
                Thread.sleep(120);reader.close();assertTrue(waiting.get(3,TimeUnit.SECONDS));assertEquals(0L,reader.bufferedBytes.get())
            } finally { reader.close();executor.shutdownNow() }
        }
    }
    @Test fun slowHeadEmitsAvailableBytesBeforeTheWholeRangeArrives() {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                return response(request).apply {
                    if(!request.getHeader("Range")!!.startsWith("bytes=0-")) throttleBody(8192,200,TimeUnit.MILLISECONDS)
                }
            } }
            val executor=Executors.newSingleThreadExecutor()
            ParallelRangeReader(client(),server.url("/file").toString(),mapOf("X-Test-Signature" to "signed-value"),0,400000,4,chunk) {}.use { reader ->
                try {
                    reader.open();val probe=ByteArray(65536);var consumed=0
                    while(consumed<probe.size) consumed+=reader.read(probe,consumed,probe.size-consumed)
                    assertArrayEquals(bytes.copyOfRange(0,65536),probe)
                    val part=ByteArray(8192)
                    val n=executor.submit<Int> { reader.read(part,0,part.size) }.get(2,TimeUnit.SECONDS)
                    assertTrue(n>0);assertArrayEquals(bytes.copyOfRange(65536,65536+n),part.copyOf(n))
                    assertTrue(reader.bufferedBytes.get()<=5L*chunk)
                } finally { executor.shutdownNow() }
            }
        }
    }
    @Test fun fiftyMbpsConsumerRemainsOrderedAndBoundedOverRepeatedFourWayRanges() {
        val source=ByteArray(48*1024*1024) { (it%251).toByte() }
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                val parts=request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                val start=parts[0].toInt();val end=minOf(parts[1].toInt(),source.lastIndex)
                return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${source.size}")
                    .setHeader("ETag","stable").setBody(Buffer().write(source,start,end-start+1))
                    .throttleBody(65536,20,TimeUnit.MILLISECONDS)
            } }
            val budget=StreamTransferBudget(4)
            val http=client().newBuilder().addInterceptor(budget.interceptor()).build()
            ParallelRangeReader(http,server.url("/high-bitrate").toString(),emptyMap(),0,-1,4,512*1024) {}.use { reader ->
                reader.open();Thread.sleep(200)
                val digest=java.security.MessageDigest.getInstance("SHA-256")
                val buffer=ByteArray(65536);var consumed=0L;var slowest=0L;var peak=0L
                val clock=System.nanoTime()
                while(true) {
                    val before=System.nanoTime();val n=reader.read(buffer,0,buffer.size)
                    slowest=maxOf(slowest,System.nanoTime()-before)
                    if(n<0) break
                    digest.update(buffer,0,n);consumed+=n;peak=maxOf(peak,reader.bufferedBytes.get())
                    val due=clock+consumed*1_000_000_000L/6_250_000L
                    val delay=due-System.nanoTime()
                    if(delay>0) TimeUnit.NANOSECONDS.sleep(delay)
                }
                assertEquals(source.size.toLong(),consumed)
                assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(source),digest.digest())
                assertTrue("No multi-second range stalls",slowest<TimeUnit.MILLISECONDS.toNanos(500))
                assertTrue(peak<=5L*512*1024);assertEquals(4,budget.peakCount)
                println("50 Mbps paced receive: ${consumed/1048576} MiB, longest read ${slowest/1000000} ms, queue peak ${peak/1024} KiB, connections ${budget.peakCount}")
            }
        }
    }
}
