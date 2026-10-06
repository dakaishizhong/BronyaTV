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

class SharedRangeCacheTest {
    private val source=ByteArray(32*1048576) { ((it*31+it/7919)%251).toByte() }
    private val chunk=1048576
    private fun digest(reader:ParallelRangeReader):ByteArray {
        val sha=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        while(true) { val n=reader.read(buffer,0,buffer.size);if(n<0) break;sha.update(buffer,0,n) };return sha.digest()
    }
    @Test fun sharedDownloaderPersistsReusesSeeksAndNeverRequestsOverlappingRanges() {
        val ranges=ConcurrentHashMap.newKeySet<Long>();val duplicates=AtomicInteger();var version="\"v1\"";var content=source
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                val parts=request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                val start=parts[0].toInt();val end=minOf(parts[1].toInt(),source.lastIndex)
                if(!ranges.add(start.toLong())) duplicates.incrementAndGet()
                return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${source.size}")
                    .setHeader("ETag",version).setBody(Buffer().write(content,start,end-start+1)).setHeadersDelay(20,TimeUnit.MILLISECONDS)
            } }
            val http=OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1)).build()
            FileRangeStore(chunk,24L*1048576).use { store ->
                ParallelRangeReader(http,server.url("/original.mkv?sig=one").toString(),emptyMap(),0,-1,8,chunk,12*1048576,store) {}.use { reader ->
                    reader.open();reader.setLoadingDemand(false)
                    // A single background worker extends disk ahead without allocating the whole disk window in RAM.
                    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                    while(!store.contains(20L*1048576,chunk) && System.nanoTime()<deadline) Thread.sleep(10)
                    assertTrue(store.contains(20L*1048576,chunk));assertTrue(reader.backgroundBytes.get()>0)
                    assertTrue(reader.bufferedBytes.get()+store.pendingBytes.get()<=15L*1048576)
                    reader.setLoadingDemand(true)
                    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source),digest(reader))
                    assertTrue(store.hitBytes.get()>0);assertEquals(0,duplicates.get())
                    assertEquals(0,reader.cancelledBackground.get())
                }
                store.drain()
                // Guarantee a committed, contiguous seek window (foreground tee may skip busy disk copies).
                assertTrue(store.persist(0,source))
                ranges.clear();duplicates.set(0)
                val start=2L*1048576+19;val length=8L*1048576
                ParallelRangeReader(http,server.url("/original.mkv?sig=one").toString(),emptyMap(),start,length,4,chunk,8*1048576,store) {}.use { reader ->
                    assertEquals(length,reader.open())
                    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source.copyOfRange(start.toInt(),(start+length).toInt())),digest(reader))
                }
                // Exactly one fresh capability/identity request; subsequent chunks must be disk hits.
                assertEquals(1,ranges.size);assertEquals(0,duplicates.get())
                version="\"v2\"";content=ByteArray(source.size) { (source[it].toInt() xor 0x5a).toByte() };ranges.clear()
                ParallelRangeReader(http,server.url("/original.mkv?sig=two").toString(),emptyMap(),0,4L*1048576,4,chunk,8*1048576,store) {}.use { reader ->
                    reader.open();assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(content.copyOf(4*1048576)),digest(reader))
                }
                assertTrue("new identity must invalidate all previous disk spans",ranges.size>=4)
            }
        }
    }
    @Test fun unavailableOrSlowDiskDoesNotHoldForegroundBytesOrTriggerAnotherDownloader() {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                val parts=request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                val start=parts[0].toInt();val end=minOf(parts[1].toInt(),source.lastIndex)
                return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${source.size}")
                    .setHeader("ETag","\"v1\"").setBody(Buffer().write(source,start,end-start+1))
            } }
            val store=object:RangeChunkStore {
                override val enabled=true
                override val aheadBytes=24L*1048576
                override fun validate(identity:RangeIdentity)=this
                override fun contains(position:Long,length:Int)=false
                override fun read(position:Long,target:ByteArray,offset:Int,length:Int)=-1
                override fun offer(position:Long,bytes:ByteArray) {} // disk busy: bounded tee simply skips
                override fun persist(position:Long,bytes:ByteArray):Boolean { Thread.sleep(250);return false }
            }
            ParallelRangeReader(OkHttpClient(),server.url("/file").toString(),emptyMap(),0,-1,8,chunk,12*1048576,store) {}.use { reader ->
                reader.open();val before=System.nanoTime()
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source),digest(reader))
                assertTrue("foreground does not join a disk writer",System.nanoTime()-before<TimeUnit.SECONDS.toNanos(3))
                assertEquals(0,reader.duplicatedRangeRequests.get())
            }
        }
    }
    @Test fun blockedDiskCommitReleasesItsHttpWorkerAndAllForegroundLanesCanRefill() {
        val committing=CountDownLatch(1);val releaseDisk=CountDownLatch(1)
        val live=ConcurrentHashMap.newKeySet<Connection>()
        val foregroundTcpWhileDiskBlocked=AtomicInteger()
        val http=OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1)).eventListenerFactory {
            object:EventListener() {
                override fun connectionAcquired(call:Call,connection:Connection) {
                    live.add(connection)
                    if(committing.count==0L && releaseDisk.count==1L) {
                        val count=live.size;foregroundTcpWhileDiskBlocked.updateAndGet { maxOf(it,count) }
                    }
                }
                override fun connectionReleased(call:Call,connection:Connection) { live.remove(connection) }
            }
        }.build()
        val smallChunk=256*1024;val window=4*1048576
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                val parts=request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                val start=parts[0].toInt();val end=minOf(parts[1].toInt(),source.lastIndex)
                return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${source.size}")
                    .setHeader("ETag","\"v1\"").setBody(Buffer().write(source,start,end-start+1))
                    .setHeadersDelay(50,TimeUnit.MILLISECONDS).throttleBody(65536,15,TimeUnit.MILLISECONDS)
            } }
            val store=object:RangeChunkStore {
                override val enabled=true
                override val aheadBytes=24L*1048576
                override fun validate(identity:RangeIdentity)=this
                override fun contains(position:Long,length:Int)=false
                override fun read(position:Long,target:ByteArray,offset:Int,length:Int)=-1
                override fun offer(position:Long,bytes:ByteArray) {}
                override fun persist(position:Long,bytes:ByteArray):Boolean {
                    committing.countDown();releaseDisk.await();return false
                }
            }
            try {
                ParallelRangeReader(http,server.url("/file").toString(),emptyMap(),0,-1,8,smallChunk,window,store) {}.use { reader ->
                    reader.open();assertTrue("disk-only body reached its separate commit thread",committing.await(5,TimeUnit.SECONDS))
                    val sha=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
                    // Cross a full window boundary; the reader releases a fully consumed head
                    // when the next read begins, rather than while returning its last bytes.
                    while(reader.snapshot().consumedPosition<window.toLong()+65536) {
                        val n=reader.read(buffer,0,buffer.size);assertTrue(n>0);sha.update(buffer,0,n)
                    }
                    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                    while(foregroundTcpWhileDiskBlocked.get()<8 && System.nanoTime()<deadline) Thread.sleep(1)
                    assertEquals("all eight foreground HTTP/1.1 TCP lanes refilled while disk commit remained blocked",8,foregroundTcpWhileDiskBlocked.get())
                    assertTrue(reader.bufferedBytes.get()<=window.toLong()+smallChunk)
                    releaseDisk.countDown()
                    while(true) { val n=reader.read(buffer,0,buffer.size);if(n<0) break;sha.update(buffer,0,n) }
                    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source),sha.digest())
                    assertEquals(0,reader.duplicatedRangeRequests.get())
                }
            } finally { releaseDisk.countDown();http.connectionPool.evictAll();http.dispatcher.executorService.shutdown() }
        }
    }
}
