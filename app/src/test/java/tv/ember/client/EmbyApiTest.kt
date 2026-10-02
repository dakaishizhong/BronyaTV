package tv.ember.client

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import tv.ember.client.data.*
import tv.ember.client.emby.EmbyApi
import tv.ember.client.network.ApiException

class EmbyApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: EmbyApi
    private lateinit var session: Session
    @Before fun setup() { server = MockWebServer(); server.start(); api = EmbyApi(OkHttpClient(), "test-device"); session = Session(server.url("/proxy/emby").toString(), "secret", "u1", "TV") }
    @After fun teardown() { server.shutdown() }
    @Test fun passwordLoginUsesEmbyContractAndPreservesProxyPrefix() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"User":{"Id":"u1","Name":"TV"},"AccessToken":"access"}"""))
        val result = api.login(session.server, "TV", "pw")
        val request = server.takeRequest(); val body = JSONObject(request.body.readUtf8())
        assertEquals("/proxy/emby/Users/AuthenticateByName", request.path)
        assertEquals("POST", request.method); assertEquals("pw", body.getString("Pw"))
        assertEquals("TV", body.getString("Username")); assertEquals("access", result.token)
        assertTrue(request.getHeader("X-Emby-Authorization")!!.contains("test-device"))
    }
    @Test fun restoredSessionUsesAuthenticatedUserValidation() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"Id":"u1","Name":"TV"}"""))
        api.validate(session)
        val r=server.takeRequest();assertEquals("/proxy/emby/Users/u1",r.path);assertEquals("secret",r.getHeader("X-Emby-Token"))
    }
    @Test fun adjacentEpisodesRequestServerOrderAcrossSeasons() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"a","Type":"Episode","SeriesId":"series","ParentIndexNumber":1,"IndexNumber":12},{"Id":"b","Type":"Episode","SeriesId":"series","ParentIndexNumber":2,"IndexNumber":1},{"Id":"c","Type":"Episode","SeriesId":"series","ParentIndexNumber":2,"IndexNumber":2}]}"""))
        val result=api.adjacentEpisodes(session,VideoItem("b","Episode","Episode",seriesId="series"))
        assertEquals("a",result.previous?.id);assertEquals("c",result.next?.id)
        val r=server.takeRequest().requestUrl!!
        assertEquals("/proxy/emby/Shows/series/Episodes",r.encodedPath);assertEquals("b",r.queryParameter("AdjacentTo"));assertNull(r.queryParameter("SeasonId"))
    }
    @Test fun playbackNegotiationExplicitlyDisablesTranscodingAndRetainsVersions() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"PlaySessionId":"p1","MediaSources":[{"Id":"4k","Name":"4K HDR","Container":"mkv","SupportsDirectPlay":true},{"Id":"1080","Container":"mp4"}]}"""))
        val info = api.playbackInfo(session, "movie")
        val r = server.takeRequest(); val b = JSONObject(r.body.readUtf8())
        assertTrue(b.getBoolean("EnableDirectPlay")); assertFalse(b.getBoolean("EnableTranscoding")); assertFalse(b.getBoolean("EnableDirectStream"))
        assertEquals(0, b.getJSONObject("DeviceProfile").getJSONArray("TranscodingProfiles").length())
        assertEquals(2, info.versions.size); assertEquals("p1", info.playSessionId)
        val spec = api.playbackSpec(session, "movie", info.versions[0], info.playSessionId)
        assertTrue(spec.url.contains("Static=true")); assertTrue(spec.url.contains("MediaSourceId=4k")); assertFalse(spec.url.contains("Transcoding"))
    }
    @Test fun cdnNeverReceivesServerTokenHeader() {
        val version = MediaVersion.parse(JSONObject("""{"Id":"v","DirectStreamUrl":"https://cdn.example.com/file.mkv?signature=signed","RequiredHttpHeaders":{"Referer":"https://provider.example"}}"""))
        val spec = api.playbackSpec(session, "m", version, "p")
        assertNull(spec.headers["X-Emby-Token"]); assertEquals("https://provider.example", spec.headers["Referer"])
        assertFalse(spec.url.contains("secret"))
    }
    @Test fun relativeStreamPreservesReverseProxy() {
        assertEquals("https://x.example/proxy/emby/Videos/m/stream", EmbyApi.resolveServerUrl("https://x.example/proxy/emby", "/Videos/m/stream"))
        assertEquals("https://x.example/proxy/emby/Videos/m/stream", EmbyApi.resolveServerUrl("https://x.example/proxy/emby", "/proxy/emby/Videos/m/stream"))
    }
    @Test fun authenticationFailureRetainsStatus() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try { api.validate(session); fail("Expected failure") } catch(e: ApiException) { assertEquals(401, e.status) }
    }
    @Test fun paginationRequestsOffsetAndReturnsTotal() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"m","Name":"Movie","Type":"Movie"}],"TotalRecordCount":95}"""))
        val p = api.items(session, "movies", 40)
        assertEquals(95, p.total); assertEquals(1, p.items.size)
        val url = server.takeRequest().requestUrl!!; assertEquals("40", url.queryParameter("StartIndex")); assertEquals("movies", url.queryParameter("ParentId"))
    }
    @Test fun resumeReportsTicksAndDirectPlay() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val v = MediaVersion.parse(JSONObject("""{"Id":"v"}"""))
        api.report(session, "Progress", "movie", PlaybackSpec("https://x/a", emptyMap(), v, "ps"), 12345, true)
        val r = server.takeRequest(); val b = JSONObject(r.body.readUtf8())
        assertEquals("/proxy/emby/Sessions/Playing/Progress", r.path)
        assertEquals(123450000L, b.getLong("PositionTicks")); assertEquals("DirectPlay", b.getString("PlayMethod")); assertTrue(b.getBoolean("IsPaused"))
    }
    @Test fun rejectsCredentialsAndQueriesInServerUrl() {
        for(value in listOf("https://user:password@example.com", "https://example.com?api_key=x", "garbage")) {
            try { EmbyApi.normalizeServer(value); fail(value) } catch(_: IllegalArgumentException) {}
        }
    }
    @Test fun missingSchemeUsesHttpsAndExplicitPortsAndProxyPathsArePreserved() {
        assertEquals("https://example.com:8443/proxy/emby",EmbyApi.normalizeServer(" example.com:8443/proxy/emby/ "))
        assertEquals("http://192.168.1.9:8096",EmbyApi.normalizeServer("http://192.168.1.9:8096"))
        assertEquals("https://example.com",EmbyApi.normalizeServer("example.com:443"))
    }
    @Test fun rejectsSourcesThatRequireTranscoding() {
        val v = MediaVersion.parse(JSONObject("""{"Id":"v","SupportsDirectPlay":false,"TranscodingUrl":"/Videos/v/master.m3u8"}"""))
        try { api.playbackSpec(session, "m", v, "p"); fail("Must reject") } catch(_: IllegalArgumentException) {}
    }
    @Test fun queryAuthenticatedDirectStreamSucceedsWhenProviderRequiresApiKey() {
        server.enqueue(MockResponse().setBody("video"))
        val version=MediaVersion.parse(JSONObject("""{"Id":"v","DirectStreamUrl":"/Videos/movie/stream.mp4?Static=true","AddApiKeyToDirectStreamUrl":true}"""))
        val spec=api.playbackSpec(session,"movie",version,"p")
        OkHttpClient().newCall(okhttp3.Request.Builder().url(spec.url).build()).execute().close()
        assertEquals("secret",server.takeRequest().requestUrl!!.queryParameter("api_key"))
    }
    @Test fun explicitCdnUrlAuthenticationRetainsProviderQueryAndDoesNotAddEmbyHeaders() {
        val version=MediaVersion.parse(JSONObject("""{"Id":"v","DirectStreamUrl":"https://cdn.example.com/a.mkv?signature=provided","AddApiKeyToDirectStreamUrl":true}"""))
        val spec=api.playbackSpec(session,"m",version,"p")
        val u=spec.url.toHttpUrl()
        assertEquals("provided",u.queryParameter("signature"));assertEquals("secret",u.queryParameter("api_key"))
        assertNull(spec.headers["X-Emby-Token"])
    }
    @Test fun providerApiKeyAndRequiredAuthenticationHeaderArePreserved() {
        val version=MediaVersion.parse(JSONObject("""{"Id":"v","DirectStreamUrl":"/Videos/m/stream.mp4?api_key=provider","AddApiKeyToDirectStreamUrl":true,"RequiredHttpHeaders":{"x-emby-token":"source-key"}}"""))
        val spec=api.playbackSpec(session,"m",version,"p")
        assertEquals("provider",spec.url.toHttpUrl().queryParameter("api_key"))
        assertEquals("source-key",spec.headers["x-emby-token"]);assertNull(spec.headers["X-Emby-Token"])
    }
    @Test fun httpMediaSourceUsesTheServerSuppliedRemotePath() {
        val version=MediaVersion.parse(JSONObject("""{"Id":"v","Protocol":"Http","Path":"https://cdn.example.com/video.mp4?signature=source","RequiredHttpHeaders":{"Referer":"https://provider.example"}}"""))
        val spec=api.playbackSpec(session,"m",version,"p")
        assertEquals("https://cdn.example.com/video.mp4?signature=source",spec.url)
        assertEquals("https://provider.example",spec.headers["Referer"]);assertNull(spec.headers["X-Emby-Token"])
    }
    @Test fun canonicalEmbyPathsAndSchemeRelativeCdnWorkBehindAProxy() {
        assertEquals("https://x.example/proxy/emby/Videos/m/stream",EmbyApi.resolveServerUrl("https://x.example/proxy/emby","/emby/Videos/m/stream"))
        assertEquals("https://cdn.example.com/a.mkv",EmbyApi.resolveServerUrl("https://x.example/emby","//cdn.example.com/a.mkv"))
    }
    @Test fun staticPlaybackUsesRequestedItemAndSelectedSourceInsteadOfMediaSourceItemId() {
        val version=MediaVersion.parse(JSONObject("""{"Id":"version-4k","ItemId":"different-item","Container":"mkv"}"""))
        val u=api.playbackSpec(session,"requested-movie",version,"fresh-session").url.toHttpUrl()
        assertEquals("/proxy/emby/Videos/requested-movie/original.mkv",u.encodedPath)
        assertEquals("version-4k",u.queryParameter("MediaSourceId"))
        assertEquals("fresh-session",u.queryParameter("PlaySessionId"))
    }
    @Test fun serverSuppliedCdnMasterPlaylistAndSignedQueryAreRetained() {
        val direct="https://cdn.example.com/transcoding-assets/master.m3u8?sig=a%2Fb%2Bc&expires=123"
        val version=MediaVersion.parse(JSONObject().put("Id","v").put("DirectStreamUrl",direct))
        assertEquals(direct,api.playbackSpec(session,"m",version,"p").url)
    }
    @Test fun explicitlyIdentifiedTranscodingUrlIsNotSelectedAsDirectPlayback() {
        val version=MediaVersion.parse(JSONObject("""{"Id":"v","DirectStreamUrl":"/Videos/m/master.m3u8","TranscodingUrl":"/Videos/m/master.m3u8","Container":"mkv"}"""))
        val u=api.playbackSpec(session,"m",version,"p").url.toHttpUrl()
        assertEquals("/proxy/emby/Videos/m/original.mkv",u.encodedPath)
        assertEquals("true",u.queryParameter("Static"))
    }
    @Test fun httpsUsesDefault443AndPreservesExplicitNonDefaultPorts() {
        for(port in listOf("",":443",":8443")) {
            val direct="https://cdn.example.com$port/video.mp4?sig=signed"
            val version=MediaVersion.parse(JSONObject().put("Id","v").put("DirectStreamUrl",direct))
            val u=api.playbackSpec(session,"m",version,"p").url.toHttpUrl()
            assertEquals("https",u.scheme);assertEquals(if(port==":8443") 8443 else 443,u.port)
            assertEquals("signed",u.queryParameter("sig"))
        }
        assertEquals(8443,EmbyApi.resolveServerUrl("https://emby.example.com:8443/emby","/Videos/m/stream.mp4").toHttpUrl().port)
    }
    @Test fun originalFileFallbackUsesActualSourceContainerAndNeverRewritesASignedCdnUrl() {
        val direct="https://cdn.example.com/stream.mp4?sig=provided"
        val version=MediaVersion.parse(JSONObject().put("Id","selected-mkv").put("Container","mkv").put("DirectStreamUrl",direct))
        assertEquals(direct,api.playbackSpec(session,"m",version,"p").url)
        val u=api.playbackSpec(session,"m",version,"fresh-p",forceOriginal=true).url.toHttpUrl()
        assertEquals("/proxy/emby/Videos/m/original.mkv",u.encodedPath)
        assertEquals("selected-mkv",u.queryParameter("MediaSourceId"))
        assertEquals("fresh-p",u.queryParameter("PlaySessionId"));assertEquals("true",u.queryParameter("Static"))
        assertEquals("secret",u.queryParameter("api_key"))
    }
}
