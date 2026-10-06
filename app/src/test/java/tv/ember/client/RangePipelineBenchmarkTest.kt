package tv.ember.client

import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.mockwebserver.Dispatcher
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.network.ParallelRangeReader
import tv.ember.client.network.StreamPolicy
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Real HTTP/1.1 sockets, per-response rate limiting, TTFB and an independent paced player. */
class RangePipelineBenchmarkTest {
    private val full=System.getenv("BRONYA_PIPELINE_MATRIX")=="1"
    @Test fun sustainedPublicNetworkMatrix() {
        val selected=System.getenv("BRONYA_PIPELINE_CASES")
        val cases=if(!selected.isNullOrBlank()) selected.split(',').map { val parts=it.split(':').map(String::toInt);Triple(parts[0],parts[1],parts[2]) } else if(full) listOf(20,50,80,100).flatMap { mbps -> listOf(20,50,100,200).flatMap { rtt ->
            listOf(2,4,8).map { lanes -> Triple(mbps,rtt,lanes) }
        } } else listOf(20,50,80,100).map { Triple(it,200,8) }
        for((mbps,rtt,lanes) in cases) for(disk in listOf(false,true)) runCase(mbps,rtt,lanes,disk)
    }
    private data class Frame(val bytes:ByteArray,val count:Int)
    internal fun runCase(mbps:Int,rtt:Int,lanes:Int,disk:Boolean,suppliedStore:RangeBenchmarkStore?=null) {
        val fixtureMiB=System.getenv("BRONYA_PIPELINE_SOURCE_MIB")?.toInt() ?: if(full) 32 else 64
        require(fixtureMiB in 16..512)
        val source=ByteArray(fixtureMiB*1048576) { ((it*31+it/7919)%251).toByte() }
        val peak=AtomicInteger();val received=AtomicLong();val longestRead=AtomicLong()
        val samples=CopyOnWriteArrayList<Int>();val eligible=CopyOnWriteArrayList<Int>();val lateEligible=CopyOnWriteArrayList<Int>()
        val live=ConcurrentHashMap.newKeySet<Connection>()
        val starts=ConcurrentHashMap.newKeySet<Long>();val duplicates=AtomicInteger()
        val intervals=java.util.TreeMap<Long,Long>()
        // Account for TTFB overhead when offering adequate effective capacity at only 2/4 lanes.
        val limitPerLane=maxOf(16,(mbps*(if(lanes==8) 1.5 else if(rtt==200 && mbps>=80) 4.0 else 3.0)/lanes).toInt())
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                val parts=requireNotNull(request.getHeader("Range")).removePrefix("bytes=").split('-')
                val offset=parts[0].toInt();val end=minOf(parts[1].toInt(),source.lastIndex)
                synchronized(intervals) {
                    val from=offset.toLong();val to=end.toLong()
                    if(intervals.floorEntry(from)?.value?.let { it>=from }==true ||
                        intervals.ceilingKey(from)?.let { it<=to }==true) duplicates.incrementAndGet()
                    intervals[from]=to;starts.add(from)
                }
                assertEquals("/original.mkv?sig=fixture",request.path);assertEquals("fixture-auth",request.getHeader("X-Test-Auth"))
                return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $offset-$end/${source.size}")
                    .setHeader("ETag","\"stable\"").setHeadersDelay(rtt.toLong(),TimeUnit.MILLISECONDS)
                    .setBody(Buffer().write(source,offset,end-offset+1))
                    .setBodyDelay(if(full && offset>0 && java.util.Random(offset.toLong() xor 7919L).nextInt(13)==0) rtt*2L else 0,TimeUnit.MILLISECONDS)
                    .throttleBody(32*1024,(32*1024*8L/limitPerLane).coerceAtLeast(1),TimeUnit.MICROSECONDS)
            } }
            val client=OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1)).eventListener(object:EventListener() {
                override fun connectionAcquired(call:Call,connection:Connection) { live.add(connection);val count=live.size;peak.updateAndGet { maxOf(it,count) } }
                override fun connectionReleased(call:Call,connection:Connection) { live.remove(connection) }
            }).build()
            val timer=Executors.newSingleThreadScheduledExecutor();val loader=Executors.newSingleThreadExecutor()
            // Exercise the production memory/bitrate/connection policy, including storage reserves.
            val plan=StreamPolicy.create(lanes,mbps*1_000_000L,512L*1048576,32L*1048576,false,disk)
            val chunk=plan.chunkBytes
            val store=suppliedStore ?: if(disk) FileRangeStore(chunk,48L*1048576) else null
            try {
                ParallelRangeReader(client,server.url("/original.mkv?sig=fixture").toString(),mapOf("X-Test-Auth" to "fixture-auth"),0,-1,lanes,chunk,
                    plan.aheadWindowBytes,store) { received.addAndGet(it.toLong()) }.use { reader ->
                    val begin=System.nanoTime();assertEquals(source.size.toLong(),reader.open())
                    // The high-bitrate jitter matrix uses one second of playable startup samples, still below
                    // the production high-bitrate BufferPolicy's five-second startup target.
                    val primeFrames=if(full && mbps>=80) (mbps*1_000_000L/8/65536+1).toInt() else 32
                    val queueCapacity=maxOf(64,primeFrames+32)
                    val queue=ArrayBlockingQueue<Frame>(queueCapacity)
                    val buffers=ArrayBlockingQueue<ByteArray>(queueCapacity+1).apply { repeat(queueCapacity+1) { add(ByteArray(65536)) } }
                    val loadFailure=AtomicReference<Throwable>()
                    val transfer=loader.submit<ByteArray> {
                        val digest=MessageDigest.getInstance("SHA-256")
                        try {
                            while(true) {
                                val buffer=buffers.take();var count=0
                                while(count<buffer.size) {
                                    val before=System.nanoTime();val n=reader.read(buffer,count,buffer.size-count)
                                    val elapsed=System.nanoTime()-before;longestRead.updateAndGet { maxOf(it,elapsed) }
                                    if(n<0) break
                                    count+=n
                                }
                                if(count==0) { buffers.put(buffer);queue.put(Frame(ByteArray(0),-1));break }
                                digest.update(buffer,0,count);queue.put(Frame(buffer,count))
                            }
                        } catch(e:Throwable) { loadFailure.set(e);queue.offer(Frame(ByteArray(0),-1)) }
                        digest.digest()
                    }
                    val primeDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                    while(queue.size<primeFrames && loadFailure.get()==null && System.nanoTime()<primeDeadline) Thread.sleep(2)
                    loadFailure.get()?.let { throw it };assertTrue("playable prebuffer",queue.size>=primeFrames)
                    val playbackStart=System.nanoTime();var deadline=playbackStart;var count=0L;var underruns=0;var memoryPeak=0L
                    timer.scheduleAtFixedRate({
                        samples.add(live.size)
                        run {
                            val s=reader.snapshot()
                            if(s.loadingDemand && s.scheduledEnd<reader.totalBytes && s.scheduledEnd<s.consumedPosition+reader.aheadWindowLimit &&
                                s.aheadBufferedBytes+chunk<=reader.aheadWindowLimit) {
                                eligible.add(s.activeRequests)
                                if(System.nanoTime()-playbackStart>2_000_000_000L) lateEligible.add(s.activeRequests)
                            }
                        }
                    },0,5,TimeUnit.MILLISECONDS)
                    while(true) {
                        var frame=queue.poll(20,TimeUnit.MILLISECONDS)
                        if(frame==null) {
                            underruns++
                            val refillDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                            while(queue.size<primeFrames && !transfer.isDone && System.nanoTime()<refillDeadline) Thread.sleep(2)
                            frame=queue.poll(10,TimeUnit.SECONDS);deadline=System.nanoTime()
                        }
                        assertNotNull("network consumer timed out",frame)
                        if(frame!!.count<0) break
                        count+=frame.count;buffers.put(frame.bytes)
                        memoryPeak=maxOf(memoryPeak,reader.bufferedBytes.get()+(store?.pendingCopyBytes ?: 0))
                        deadline+=frame.count*8_000L/mbps
                        val delay=deadline-System.nanoTime()
                        if(delay>0) TimeUnit.NANOSECONDS.sleep(delay)
                    }
                    timer.shutdownNow();loadFailure.get()?.let { throw it }
                    val elapsed=(System.nanoTime()-playbackStart)/1e9
                    val expectedDigest=MessageDigest.getInstance("SHA-256").digest(source)
                    val actualDigest=transfer.get(5,TimeUnit.SECONDS)
                    val result="{\"baseline\":false,\"disk\":$disk,\"storeImplementation\":\"${if(suppliedStore!=null) "SimpleCache" else if(disk) "FileRangeStore" else "memory"}\",\"consumerMbps\":$mbps,\"ttfbMs\":$rtt,\"lanes\":$lanes,\"perLaneMbps\":$limitPerLane,\"deliveredMbps\":${count*8/elapsed/1e6},\"meanActiveTcp\":${samples.average()},\"zeroTcpPercent\":${samples.count { it==0 }*100.0/samples.size},\"eligibleSamples\":${eligible.size},\"eligibleMeanRange\":${if(eligible.isEmpty()) 0.0 else eligible.average()},\"lateEligibleSamples\":${lateEligible.size},\"lateEligibleMeanRange\":${if(lateEligible.isEmpty()) 0.0 else lateEligible.average()},\"eligibleZeroRangePercent\":${if(eligible.isEmpty()) 0.0 else eligible.count { it==0 }*100.0/eligible.size},\"peakTcp\":${peak.get()},\"underruns\":$underruns,\"longestReadMs\":${longestRead.get()/1e6},\"memoryPeakBytes\":$memoryPeak,\"requests\":${starts.size},\"duplicates\":${duplicates.get()},\"startupMs\":${(playbackStart-begin)/1e6},\"sourceBytes\":${source.size},\"chunkBytes\":$chunk,\"aheadWindowBytes\":${reader.aheadWindowLimit},\"rangeBudgetBytes\":${plan.budgetBytes},\"playablePrebufferBytes\":${primeFrames*65536},\"playableBufferCapacityBytes\":${queueCapacity*65536},\"sha256\":\"${actualDigest.joinToString("") { "%02x".format(it) }}\",\"expectedSha256\":\"${expectedDigest.joinToString("") { "%02x".format(it) }}\"}"
                    println("PIPELINE_RESULT $result")
                    System.getenv("BRONYA_PIPELINE_REPORT")?.let { java.io.File(it).appendText(result+"\n") }
                    assertEquals(source.size.toLong(),count);assertEquals(count,received.get())
                    assertArrayEquals(expectedDigest,actualDigest)
                    assertEquals(0,duplicates.get());assertTrue(peak.get()<=lanes)
                    run {
                        assertEquals("paced consumer must not underrun: $result",0,underruns);assertTrue(memoryPeak<=32L*1048576)
                        assertTrue("sustained delivery meets the consumer: $result",count*8/elapsed/1e6>=mbps*0.98)
                        if(!disk && eligible.size>20) assertTrue("workers maintain configured concurrency when the window has room: $result",eligible.average()>=lanes*0.9)
                        if(!disk && fixtureMiB>=256 && lanes==8) {
                            assertTrue("measure sustained demand beyond the initial worker wave",lateEligible.size>20)
                            assertTrue("late workers maintain configured concurrency",lateEligible.average()>=lanes*0.9)
                        }
                    }
                }
            } finally {
                timer.shutdownNow();loader.shutdownNow();store?.close();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
            }
        }
    }
}
