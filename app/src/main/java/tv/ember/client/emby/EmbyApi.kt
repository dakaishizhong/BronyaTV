package tv.ember.client.emby

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tv.ember.client.data.*
import tv.ember.client.network.ApiException
import tv.ember.client.network.awaitResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmbyApi(private val client: OkHttpClient, private val deviceId: String) {
    var metadataStore: tv.ember.client.cache.MetadataStore?=null
    private suspend fun metadata(s: Session,path: String,query: Map<String,String> = emptyMap()): String {
        val store=metadataStore ?: return request(s.server,path,s.token,query)
        return store.fresh(store.key(s,path,query)) { request(s.server,path,s.token,query) }
    }
    private suspend fun cached(s: Session,path: String,query: Map<String,String> = emptyMap())=metadataStore?.let { it.cached(it.key(s,path,query)) }
    suspend fun cachedDetail(s: Session,id: String): VideoItem?=withContext(Dispatchers.IO) { cached(s,"Users/${s.userId}/Items/$id")?.let { runCatching { VideoItem.parse(JSONObject(it)) }.getOrNull() } }
    suspend fun cachedItems(s: Session,query: BrowseQuery): ItemPage?=withContext(Dispatchers.IO) { cached(s,"Users/${s.userId}/Items",query.parameters())?.let { runCatching { val j=JSONObject(it);ItemPage(j.optJSONArray("Items").objects().map(VideoItem::parse),j.optInt("TotalRecordCount")) }.getOrNull() } }
    suspend fun browse(s: Session,query: BrowseQuery): ItemPage=withContext(Dispatchers.IO) {
        val j=JSONObject(metadata(s,"Users/${s.userId}/Items",query.parameters()))
        ItemPage(j.optJSONArray("Items").objects().map(VideoItem::parse),j.optInt("TotalRecordCount"))
    }
    suspend fun facets(s: Session,query: BrowseQuery): BrowseFacets=kotlinx.coroutines.coroutineScope {
        suspend fun values(path: String): Pair<List<String>,Boolean> = try {
            val names=mutableListOf<String>();var start=0
            do {
                val q=mutableMapOf("UserId" to s.userId,"Recursive" to "true","Limit" to "200","StartIndex" to "$start","EnableTotalRecordCount" to "true","SortBy" to "SortName","SortOrder" to "Ascending")
                if(query.parent.isNotBlank()) q["ParentId"]=query.parent
                query.parameters()["IncludeItemTypes"]?.let { q["IncludeItemTypes"]=it }
                val j=withContext(Dispatchers.IO) { JSONObject(metadata(s,path,q)) }
                val list=j.optJSONArray("Items").objects();names+=list.map { it.optString("Name") }.filter(String::isNotBlank)
                start+=list.size
                val more=list.isNotEmpty() && start<j.optInt("TotalRecordCount",start)
            } while(more)
            names.distinct() to true
        } catch(e: kotlinx.coroutines.CancellationException) { throw e } catch(e: ApiException) { if(e.status !in listOf(400,404,405,501)) throw e;emptyList<String>() to false }
        val genres=async { values("Genres") };val years=async { values("Years") }
        val g=genres.await();val y=years.await();BrowseFacets(g.first,y.first,g.second,y.second)
    }
    companion object {
        fun normalizeServer(input: String): String {
            val cleaned = input.trim().trimEnd('/')
            val explicitScheme=cleaned.contains("://")
            val value = if(explicitScheme) cleaned else "https://${cleaned}"
            val u = value.toHttpUrlOrNull() ?: throw IllegalArgumentException(Tr.text(UiText.ENTER_A_COMPLETE_SERVER_URL_SUCH_016))
            require(explicitScheme || u.host.contains('.') || u.host.contains(':') || u.host=="localhost") { Tr.text(UiText.ENTER_THE_SERVER_HOSTNAME_OR_IP_017) }
            require(u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null) { Tr.text(UiText.SERVER_URL_MUST_NOT_CONTAIN_A_018) }
            return u.toString().trimEnd('/')
        }
        fun sameOrigin(a: String, b: String): Boolean {
            val x = a.toHttpUrlOrNull() ?: return false
            val y = b.toHttpUrlOrNull() ?: return false
            return x.scheme == y.scheme && x.host == y.host && x.port == y.port
        }
        fun resolveServerUrl(server: String, path: String): String {
            if (path.startsWith("https://") || path.startsWith("http://")) return path.toHttpUrl().toString()
            val base = (server.trimEnd('/') + "/").toHttpUrl()
            // /Videos is commonly returned without a reverse-proxy prefix; preserve the configured prefix.
            val prefix = base.encodedPath.trimEnd('/')
            val relative = when {
                path.startsWith("//") -> path
                prefix.endsWith("/emby", true) && path.startsWith("/emby/", true) && !path.startsWith("${prefix}/", true) -> path.substring(6)
                path.startsWith('/') && prefix.isNotEmpty() && !path.startsWith("${prefix}/", true) -> path.trimStart('/')
                else -> path
            }
            return requireNotNull(base.resolve(relative)).toString()
        }
    }
    fun authHeader(token: String = "") = "MediaBrowser Client=\"BronyaTV\", Device=\"Android TV\", DeviceId=\"${deviceId}\", Version=\"${tv.ember.client.BuildConfig.VERSION_NAME}\"" +
        if (token.isBlank()) "" else ", Token=\"${token.replace("\"", "")}\""
    private fun url(server: String, path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val b = (server.trimEnd('/') + "/").toHttpUrl().newBuilder().addPathSegments(path.trimStart('/'))
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }
    private suspend fun request(server: String, path: String, token: String = "", query: Map<String, String> = emptyMap(), body: JSONObject? = null): String {
        val b = Request.Builder().url(url(server, path, query)).header("X-Emby-Authorization", authHeader(token))
        if (token.isNotBlank()) b.header("X-Emby-Token", token)
        if (body != null) b.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        return withContext(Dispatchers.IO) { client.newCall(b.build()).awaitResponse().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, when (response.code) {
                401, 403 -> Tr.text(UiText.SESSION_EXPIRED_OR_ACCESS_DENIED_HTTP_019 ,(response.code))
                404 -> Tr.text(UiText.SERVER_RESOURCE_NOT_FOUND_HTTP_CHECK_020)
                else -> Tr.text(UiText.SERVER_REQUEST_FAILED_HTTP_021 ,(response.code))
            })
            response.body?.string().orEmpty()
        } }
    }
    suspend fun login(serverInput: String, username: String, password: String): Session = withContext(Dispatchers.IO) {
        val server = normalizeServer(serverInput)
        val j = JSONObject(request(server, "Users/AuthenticateByName", body = JSONObject().put("Username", username).put("Pw", password)))
        val u = j.getJSONObject("User")
        return@withContext Session(server, j.getString("AccessToken"), u.getString("Id"), u.optString("Name", username))
    }
    suspend fun publicUsers(serverInput: String): List<PublicUser> = withContext(Dispatchers.IO) {
        JSONArray(request(normalizeServer(serverInput),"Users/Public")).objects().mapNotNull {
            val id=it.optString("Id");val name=it.optString("Name")
            if(id.isBlank() || name.isBlank()) null else PublicUser(id,name,it.optBoolean("HasPassword"),
                it.optString("Tag"),it.optString("Description"),it.optString("AvatarInitials"))
        }.distinctBy { it.id }
    }
    suspend fun validate(s: Session) { request(s.server, "Users/${s.userId}", s.token) }
    suspend fun views(s: Session): List<VideoItem> = withContext(Dispatchers.IO) { JSONObject(metadata(s, "Users/${s.userId}/Views")).optJSONArray("Items").objects().map(VideoItem::parse) }
    suspend fun cachedViews(s: Session): List<VideoItem>?=withContext(Dispatchers.IO) { cached(s,"Users/${s.userId}/Views")?.let { JSONObject(it).optJSONArray("Items").objects().map(VideoItem::parse) } }
    private val resumeQuery=mapOf("Limit" to "16","MediaTypes" to "Video","Fields" to "Overview,MediaSources,Studios,OriginalTitle,Genres,CommunityRating","EnableImageTypes" to "Primary,Backdrop")
    private val latestQuery=mapOf("Limit" to "24","IncludeItemTypes" to "Movie,Episode","Fields" to "Overview,MediaSources,Studios,OriginalTitle,Genres,CommunityRating")
    private fun latestQuery(parent: String)=if(parent.isBlank()) latestQuery else latestQuery+("ParentId" to parent)
    suspend fun resume(s: Session): List<VideoItem> = withContext(Dispatchers.IO) { JSONObject(metadata(s,"Users/${s.userId}/Items/Resume",resumeQuery)).optJSONArray("Items").objects().map(VideoItem::parse) }
    suspend fun latest(s: Session,parent: String=""): List<VideoItem> = withContext(Dispatchers.IO) { JSONArray(metadata(s,"Users/${s.userId}/Items/Latest",latestQuery(parent))).objects().map(VideoItem::parse) }
    suspend fun cachedResume(s: Session): List<VideoItem>?=withContext(Dispatchers.IO) { cached(s,"Users/${s.userId}/Items/Resume",resumeQuery)?.let { JSONObject(it).optJSONArray("Items").objects().map(VideoItem::parse) } }
    suspend fun cachedLatest(s: Session,parent: String=""): List<VideoItem>?=withContext(Dispatchers.IO) { cached(s,"Users/${s.userId}/Items/Latest",latestQuery(parent))?.let { JSONArray(it).objects().map(VideoItem::parse) } }
    suspend fun items(s: Session, parent: String, start: Int = 0, search: String = "", sort: String = "SortName", types: String = "", favorite: Boolean = false): ItemPage = withContext(Dispatchers.IO) {
        require(sort in listOf("SortName","DateCreated","PremiereDate","CommunityRating"))
        val q = mutableMapOf("Limit" to "40", "StartIndex" to "${start}", "Fields" to "Overview,MediaSources", "SortBy" to sort, "SortOrder" to if(sort=="SortName") "Ascending" else "Descending", "EnableTotalRecordCount" to "true")
        if (parent.isNotBlank()) q["ParentId"] = parent
        if (search.isNotBlank()) { q["SearchTerm"] = search; q["Recursive"] = "true"; q["IncludeItemTypes"] = "Movie,Series,Episode" }
        if(types.isNotBlank()) { q["IncludeItemTypes"]=types; q["Recursive"]="true" }
        if(favorite) { q["Filters"]="IsFavorite";q["Recursive"]="true" }
        val j = JSONObject(request(s.server, "Users/${s.userId}/Items", s.token, q))
        return@withContext ItemPage(j.optJSONArray("Items").objects().map(VideoItem::parse), j.optInt("TotalRecordCount"))
    }
    suspend fun detail(s: Session, id: String) = withContext(Dispatchers.IO) { VideoItem.parse(JSONObject(metadata(s, "Users/${s.userId}/Items/${id}"))) }
    suspend fun adjacentEpisodes(s: Session, item: VideoItem): EpisodeNeighbors = withContext(Dispatchers.IO) {
        if(item.type!="Episode" || item.seriesId.isBlank()) return@withContext EpisodeNeighbors()
        val j=JSONObject(request(s.server,"Shows/${item.seriesId}/Episodes",s.token,mapOf(
            "UserId" to s.userId,"AdjacentTo" to item.id,"Fields" to "Overview,MediaSources",
            "EnableUserData" to "true")))
        return@withContext EpisodeNeighbors.from(item,j.optJSONArray("Items").objects().map(VideoItem::parse))
    }
    suspend fun playbackInfo(s: Session, id: String, sourceId: String = ""): PlaybackInfo = withContext(Dispatchers.IO) {
        val profile = JSONObject().put("Name", "BronyaTV Direct Play")
            .put("MaxStreamingBitrate", 1_000_000_000)
            .put("DirectPlayProfiles", JSONArray().put(JSONObject().put("Type", "Video").put("Container", "mkv,mp4,m4v,mov,webm,avi,ts,mpegts")))
            .put("TranscodingProfiles", JSONArray())
            .put("SubtitleProfiles", JSONArray().put(JSONObject().put("Format", "srt").put("Method", "External"))
                .put(JSONObject().put("Format", "ass").put("Method", "External"))
                .put(JSONObject().put("Format", "ssa").put("Method", "Embed")))
        val body = JSONObject().put("UserId", s.userId).put("DeviceProfile", profile).put("EnableDirectPlay", true)
            .put("EnableDirectStream", false).put("EnableTranscoding", false).put("IsPlayback", true)
            .put("AutoOpenLiveStream", false).put("MaxStreamingBitrate", 1_000_000_000)
        if (sourceId.isNotBlank()) body.put("MediaSourceId", sourceId)
        val j = JSONObject(request(s.server, "Items/${id}/PlaybackInfo", s.token, body = body))
        if (j.optString("ErrorCode").isNotBlank()) throw IllegalStateException(Tr.text(UiText.SERVER_CANNOT_PROVIDE_THE_ORIGINAL_SOURCE_022 ,(j.optString("ErrorCode"))))
        return@withContext PlaybackInfo(j.optJSONArray("MediaSources").objects().map(MediaVersion::parse), j.optString("PlaySessionId"))
    }
    fun playbackSpec(s: Session, itemId: String, version: MediaVersion, playSessionId: String, forceOriginal: Boolean = false): PlaybackSpec {
        require(!version.requiresOpening) { Tr.text(UiText.THIS_SOURCE_REQUIRES_A_LIVE_SESSION_023) }
        require(version.directPlay) { Tr.text(UiText.SERVER_DOES_NOT_ALLOW_DIRECT_PLAY_024) }
        // DirectStreamUrl is the server's playback result. A CDN may legitimately use
        // master.m3u8 or "transcod" in its path; do not discard it based on its name.
        val direct = if(forceOriginal) null else version.directUrl.takeIf { it.isNotBlank() &&
            (version.transcodingUrl.isBlank() || resolveServerUrl(s.server, it) != resolveServerUrl(s.server, version.transcodingUrl)) }
            ?: version.path.takeIf { version.protocol in listOf("Http", "http", "1") && it.toHttpUrlOrNull() != null }
        var address = if (direct != null) resolveServerUrl(s.server, direct) else {
            val container = version.container.substringBefore(',').lowercase().filter { it.isLetterOrDigit() }.ifBlank { "mkv" }
            // Match PlaybackInfo's item ID; the selected version belongs in MediaSourceId.
            url(s.server, "Videos/${itemId}/original.${container}", mapOf(
                "Static" to "true", "MediaSourceId" to version.id, "DeviceId" to deviceId, "PlaySessionId" to playSessionId, "api_key" to s.token)).toString()
        }
        // Follow the server's explicit URL-authentication instruction, including approved CDN URLs.
        if (direct != null && version.addApiKeyToDirectUrl) {
            val u = address.toHttpUrl()
            if (u.queryParameterNames.none { it.equals("api_key", true) }) address = u.newBuilder().addQueryParameter("api_key", s.token).build().toString()
        }
        val headers = version.headers.toMutableMap()
        if (sameOrigin(s.server, address)) {
            if(headers.keys.none { it.equals("X-Emby-Token", true) }) headers["X-Emby-Token"] = s.token
            if(headers.keys.none { it.equals("X-Emby-Authorization", true) }) headers["X-Emby-Authorization"] = authHeader(s.token)
        }
        return PlaybackSpec(address, headers, version, playSessionId)
    }
    fun subtitleUrl(s: Session, itemId: String, version: MediaVersion, stream: MediaStream): String {
        val address = if (stream.url.isNotBlank()) resolveServerUrl(s.server, stream.url) else
            url(s.server, "Videos/${itemId}/${version.id}/Subtitles/${stream.index}/Stream.${if (stream.codec in listOf("ass", "ssa")) "ass" else "srt"}").toString()
        return if (sameOrigin(s.server, address)) address.toHttpUrl().newBuilder().setQueryParameter("api_key", s.token).build().toString() else address
    }
    fun imageUrl(s: Session, item: VideoItem,width: Int=360): String = url(s.server, "Items/${item.imageId}/Images/Primary", mapOf("maxWidth" to width.coerceIn(48,1920).toString(), "quality" to "85", "tag" to item.imageTag)).toString()
    fun landscapeUrl(s: Session,item: VideoItem,large: Boolean=false,width: Int=if(large) 1280 else 480): String =
        if(item.backdropTag.isBlank()) url(s.server,"Items/${item.imageId}/Images/Primary",mapOf("maxWidth" to width.coerceIn(48,1920).toString(),"quality" to "85","tag" to item.imageTag)).toString()
        else url(s.server,"Items/${item.backdropId}/Images/Backdrop/0",mapOf("maxWidth" to width.coerceIn(48,1920).toString(),"quality" to "85","tag" to item.backdropTag)).toString()
    suspend fun similar(s: Session,id: String): List<VideoItem> = withContext(Dispatchers.IO) { JSONObject(metadata(s,"Items/${id}/Similar",
        mapOf("UserId" to s.userId,"Limit" to "12","Fields" to "Overview,MediaSources"))).optJSONArray("Items").objects().map(VideoItem::parse) }
    suspend fun report(s: Session, event: String, itemId: String, spec: PlaybackSpec, positionMs: Long, paused: Boolean) {
        request(s.server, "Sessions/Playing${if (event.isBlank()) "" else "/${event}"}", s.token, body = JSONObject()
            .put("ItemId", itemId).put("MediaSourceId", spec.version.id).put("PlaySessionId", spec.playSessionId)
            .put("PositionTicks", positionMs.coerceAtLeast(0) * 10_000).put("IsPaused", paused).put("CanSeek", true).put("PlayMethod", "DirectPlay"))
    }
}
