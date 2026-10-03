package tv.ember.client

import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.network.StreamTransferBudget
import java.util.concurrent.*

class StreamTransferBudgetTest {
    @Test fun foregroundAndReadAheadShareTheSelectedTotalAtTwoFourAndEight() {
        for(limit in listOf(2,4,8)) MockWebServer().use { server ->
            val budget=StreamTransferBudget(limit)
            val client=OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1)).build()
            val background=client.newBuilder().addInterceptor(budget.interceptor(true)).build()
            val foreground=client.newBuilder().addInterceptor(budget.interceptor()).build()
            val executor=Executors.newFixedThreadPool(limit*2)
            repeat(limit*2) { server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(128*1024))).throttleBody(8192,10,TimeUnit.MILLISECONDS)) }
            try {
                val reads=(0 until limit*2).map { index -> executor.submit<Boolean> {
                    runCatching { (if(index%2==0) background else foreground).newCall(Request.Builder().url(server.url("/file")).build())
                        .execute().use { it.body!!.bytes() } }.isSuccess
                } }
                reads.forEachIndexed { index,read -> if(index%2==1) assertTrue(read.get(10,TimeUnit.SECONDS)) else read.get(10,TimeUnit.SECONDS) }
                assertTrue(budget.peakCount in 1..limit);assertEquals(0,budget.activeCount)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun foregroundCancelsASlowBackgroundResponseAndDoesNotWaitForItsChunk() {
        MockWebServer().use { server ->
            val budget=StreamTransferBudget(1)
            val background=OkHttpClient.Builder().addInterceptor(budget.interceptor(true)).build()
            val foreground=background.newBuilder().apply { interceptors().clear() }.addInterceptor(budget.interceptor()).build()
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1024*1024))).throttleBody(1024,1,TimeUnit.SECONDS))
            server.enqueue(MockResponse().setBody("playback"))
            val executor=Executors.newFixedThreadPool(2)
            try {
                val slow=background.newCall(Request.Builder().url(server.url("/ahead")).build())
                val waiting=executor.submit { runCatching { slow.execute().use { it.body!!.bytes() } } }
                assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))
                val playback=executor.submit<String> { foreground.newCall(Request.Builder().url(server.url("/now")).build())
                    .execute().use { it.body!!.string() } }
                assertEquals("playback",playback.get(2,TimeUnit.SECONDS))
                waiting.get(2,TimeUnit.SECONDS);assertTrue(slow.isCanceled());assertEquals(1,budget.peakCount);assertEquals(0,budget.activeCount)
            } finally { executor.shutdownNow() }
        }
    }
}
