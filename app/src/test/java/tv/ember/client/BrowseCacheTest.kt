package tv.ember.client

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import tv.ember.client.cache.*
import tv.ember.client.data.Session
import tv.ember.client.emby.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class BrowseCacheTest {
    @Test fun everyPageRetainsServerSideFiltersAndLibraryParent() {
        val query=BrowseQuery(parent="library",types="Movie",genre="Science fiction",year="2024",played=false,sort=BrowseSort.ADDED,descending=true)
        val p=query.copy(start=40).parameters()
        assertEquals("library",p["ParentId"]);assertEquals("40",p["StartIndex"]);assertEquals("40",p["Limit"])
        assertEquals("Science fiction",p["Genres"]);assertEquals("2024",p["Years"]);assertEquals("false",p["IsPlayed"])
        assertEquals("DateCreated",p["SortBy"]);assertEquals("Descending",p["SortOrder"]);assertEquals("true",p["Recursive"])
        assertEquals(query,BrowseQuery.parse(query.json()))
        assertNull(BrowseQuery(parent="folder").parameters()["Recursive"])
    }
    @Test fun allVideoSearchPreservesTheExistingMediaTypeScope() {
        assertEquals("Movie,Series,Episode",BrowseQuery(search="Ocean").parameters()["IncludeItemTypes"])
        assertEquals("Movie",BrowseQuery(search="Ocean",types="Movie").parameters()["IncludeItemTypes"])
        assertNull(BrowseQuery(parent="library").parameters()["IncludeItemTypes"])
    }
    @Test fun facetsComeFromDocumentedScopedEndpointsIncludingSubsequentPages()=runBlocking {
        val server=MockWebServer()
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val u=request.requestUrl!!
                assertEquals("lib",u.queryParameter("ParentId"));assertEquals("u",u.queryParameter("UserId"));assertEquals("Movie",u.queryParameter("IncludeItemTypes"))
                return MockResponse().setBody(if(u.encodedPath.endsWith("Genres")) {
                    if(u.queryParameter("StartIndex")=="0") """{"Items":[{"Name":"Adventure"}],"TotalRecordCount":2}""" else """{"Items":[{"Name":"Drama"}],"TotalRecordCount":2}"""
                } else """{"Items":[{"Name":"1994"}],"TotalRecordCount":1}""")
            }
        }
        server.start()
        try {
            val facets=EmbyApi(OkHttpClient(),"test").facets(Session(server.url("/").toString(),"token","u","TV"),BrowseQuery(parent="lib",types="Movie"))
            assertEquals(listOf("Adventure","Drama"),facets.genres);assertEquals(listOf("1994"),facets.years);assertEquals(3,server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun unsupportedFacetEndpointsAreReportedWithoutInventingValues()=runBlocking {
        val server=MockWebServer();server.enqueue(MockResponse().setResponseCode(404));server.enqueue(MockResponse().setResponseCode(404));server.start()
        try {
            val result=EmbyApi(OkHttpClient(),"test").facets(Session(server.url("/").toString(),"t","u","TV"),BrowseQuery())
            assertTrue(result.genres.isEmpty());assertTrue(result.years.isEmpty());assertFalse(result.genresSupported);assertFalse(result.yearsSupported)
        } finally { server.shutdown() }
    }
    @Test fun persistentMetadataExcludesProgressPlaybackUrlsAndCredentials() {
        val raw="""{"Id":"m","Name":"Movie","Overview":"Story","UserData":{"PlaybackPositionTicks":200,"Played":true},"PlaySessionId":"session","MediaSources":[{"Id":"v","Name":"Version","Path":"/private/movie.mkv","DirectStreamUrl":"https://cdn/?token=secret","TranscodingUrl":"secret","RequiredHttpHeaders":{"X-Emby-Token":"secret"},"Bitrate":50000000,"MediaStreams":[{"Type":"Video","Codec":"hevc","Height":2160,"DeliveryUrl":"signed"}]}]}"""
        val clean=JSONObject(MetadataStore.sanitize(raw));assertEquals("Story",clean.getString("Overview"))
        assertFalse(clean.has("UserData"));assertFalse(clean.has("PlaySessionId"));assertFalse(clean.toString().contains("secret"));assertFalse(clean.toString().contains("signed"))
        assertEquals(2160,clean.getJSONArray("MediaSources").getJSONObject(0).getJSONArray("MediaStreams").getJSONObject(0).getInt("Height"))
    }
    @Test fun diskCapacityEvictionClearAndAccountIsolationRemainSeparate() {
        val root=Files.createTempDirectory("bronya-cache").toFile()
        try {
            var cap=10L;val images=BoundedDiskStore(java.io.File(root,"images")) { cap };val metadata=BoundedDiskStore(java.io.File(root,"metadata")) { 100L }
            val a=BoundedDiskStore.namespace("https://one/emby","u");val b=BoundedDiskStore.namespace("https://two/emby","u");val c=BoundedDiskStore.namespace("https://one/emby","other")
            assertNotEquals(a,b);assertNotEquals(a,c)
            images.write(a,ByteArray(6));images.write(b,ByteArray(6));assertTrue(images.usedBytes()<=10);assertNull(images.read(c))
            metadata.write(a,"title".toByteArray());images.clear();assertEquals(0L,images.usedBytes());assertEquals("title",metadata.read(a)!!.toString(Charsets.UTF_8))
            cap=0;images.write(a,byteArrayOf(1));assertNull(images.read(a))
        } finally { root.deleteRecursively() }
    }
    @Test fun concurrentIdenticalRequestsShareWorkAndLastObserverCancels()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val shared=SharedRequests<String,Int>(scope);val calls=AtomicInteger()
        try {
            val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
            val one=async { shared.get("image") { calls.incrementAndGet();entered.complete(Unit);gate.await();42 } }
            entered.await();val two=async { shared.get("image") { error("Duplicate") } };yield();one.cancelAndJoin();gate.complete(Unit)
            assertEquals(42,two.await());assertEquals(1,calls.get())
            val cancelled=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>()
            val last=launch { shared.get("unused") { try { started.complete(Unit);awaitCancellation() } finally { cancelled.complete(Unit) } } }
            started.await();last.cancelAndJoin();withTimeout(2000) { cancelled.await() }
        } finally { scope.cancel() }
    }
    @Test fun transientProgressBridgesServerRaceThenExpiresWithoutCrossAccountLeak() {
        var now=0L;val progress=tv.ember.client.data.TransientProgress { now }
        val session=Session("https://one/emby","token","user","TV");val item=tv.ember.client.data.VideoItem("movie","Movie","Movie",resumeTicks=1)
        progress.update(session,item.id,4000,false)
        assertEquals(40000000L,progress.apply(session,item).resumeTicks)
        assertEquals(1L,progress.apply(session.copy(userId="other"),item).resumeTicks)
        now=31000;assertEquals(1L,progress.apply(session,item).resumeTicks)
        progress.update(session,item.id,12000,true);assertTrue(progress.finished(session,item.id));assertEquals(0L,progress.apply(session,item).resumeTicks)
    }

}
