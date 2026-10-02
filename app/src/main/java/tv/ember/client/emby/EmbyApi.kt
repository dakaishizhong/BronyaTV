package tv.ember.client.emby

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmbyApi(private val client: OkHttpClient, private val deviceId: String) {
    companion object {
        fun normalizeServer(input: String): String {
            val cleaned = input.trim().trimEnd('/')
            val explicitScheme=cleaned.contains("://")
            val value = if(explicitScheme) cleaned else "https://$cleaned"
            val u = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("请输入完整服务器地址，例如 https://emby.example.com")
            require(explicitScheme || u.host.contains('.') || u.host.contains(':') || u.host=="localhost") { "请输入服务器域名或 IP 地址" }
            require(u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null) { "服务器地址不能包含密码、查询参数或片段" }
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
                prefix.endsWith("/emby", true) && path.startsWith("/emby/", true) && !path.startsWith("$prefix/", true) -> path.substring(6)
                path.startsWith('/') && prefix.isNotEmpty() && !path.startsWith("$prefix/", true) -> path.trimStart('/')
                else -> path
            }
            return requireNotNull(base.resolve(relative)).toString()
        }
    }
    fun authHeader(token: String = "") = "MediaBrowser Client=\"BronyaTV\", Device=\"Android TV\", DeviceId=\"$deviceId\", Version=\"${tv.ember.client.BuildConfig.VERSION_NAME}\"" +
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
                401, 403 -> "登录失效或没有访问权限（HTTP ${response.code}）"
                404 -> "服务器资源不存在（HTTP 404），请检查服务器地址和代理路径"
                else -> "服务器请求失败（HTTP ${response.code}）"
            })
            response.body?.string().orEmpty()
        } }
    }
    suspend fun login(serverInput: String, username: String, password: String): Session {
        val server = normalizeServer(serverInput)
        val j = JSONObject(request(server, "Users/AuthenticateByName", body = JSONObject().put("Username", username).put("Pw", password)))
        val u = j.getJSONObject("User")
        return Session(server, j.getString("AccessToken"), u.getString("Id"), u.optString("Name", username))
    }
    suspend fun tokenLogin(serverInput: String, token: String, userId: String = ""): Session {
        val server = normalizeServer(serverInput)
        require(token.isNotBlank()) { "请输入 Token" }
        val resolvedId = if(userId.isNotBlank()) userId.trim() else {
            val sessions = JSONArray(request(server, "Sessions", token.trim(), mapOf("DeviceId" to deviceId)))
            sessions.objects().firstOrNull { it.optString("DeviceId") == deviceId && it.optString("UserId").isNotBlank() }
                ?.getString("UserId") ?: throw IllegalArgumentException("该 Token 无法自动确定用户，请填写用户 ID（服务器 API Key 必须指定用户）")
        }
        val path = "Users/${HttpUrl.Builder().scheme("https").host("x").addPathSegment(resolvedId).build().encodedPath.trimStart('/')}"
        val u = JSONObject(request(server, path, token.trim()))
        return Session(server, token.trim(), u.getString("Id"), u.optString("Name", "Emby 用户"))
    }
    suspend fun validate(s: Session) { request(s.server, "Users/${s.userId}", s.token) }
    suspend fun views(s: Session): List<VideoItem> = JSONObject(request(s.server, "Users/${s.userId}/Views", s.token)).optJSONArray("Items").objects().map(VideoItem::parse)
    suspend fun resume(s: Session): List<VideoItem> = JSONObject(request(s.server, "Users/${s.userId}/Items/Resume", s.token,
        mapOf("Limit" to "16", "MediaTypes" to "Video", "Fields" to "Overview,MediaSources", "EnableImageTypes" to "Primary"))).optJSONArray("Items").objects().map(VideoItem::parse)
    suspend fun latest(s: Session): List<VideoItem> = JSONArray(request(s.server, "Users/${s.userId}/Items/Latest", s.token,
        mapOf("Limit" to "24", "IncludeItemTypes" to "Movie,Episode", "Fields" to "Overview,MediaSources"))).objects().map(VideoItem::parse)
    suspend fun items(s: Session, parent: String, start: Int = 0, search: String = "", sort: String = "SortName"): ItemPage {
        require(sort in listOf("SortName","DateCreated","PremiereDate","CommunityRating"))
        val q = mutableMapOf("Limit" to "40", "StartIndex" to "$start", "Fields" to "Overview,MediaSources", "SortBy" to sort, "SortOrder" to if(sort=="SortName") "Ascending" else "Descending", "EnableTotalRecordCount" to "true")
        if (parent.isNotBlank()) q["ParentId"] = parent
        if (search.isNotBlank()) { q["SearchTerm"] = search; q["Recursive"] = "true"; q["IncludeItemTypes"] = "Movie,Series,Episode" }
        val j = JSONObject(request(s.server, "Users/${s.userId}/Items", s.token, q))
        return ItemPage(j.optJSONArray("Items").objects().map(VideoItem::parse), j.optInt("TotalRecordCount"))
    }
    suspend fun detail(s: Session, id: String) = VideoItem.parse(JSONObject(request(s.server, "Users/${s.userId}/Items/$id", s.token)))
    suspend fun playbackInfo(s: Session, id: String, sourceId: String = ""): PlaybackInfo {
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
        val j = JSONObject(request(s.server, "Items/$id/PlaybackInfo", s.token, body = body))
        if (j.optString("ErrorCode").isNotBlank()) throw IllegalStateException("服务器无法提供原始片源：${j.optString("ErrorCode")}")
        return PlaybackInfo(j.optJSONArray("MediaSources").objects().map(MediaVersion::parse), j.optString("PlaySessionId"))
    }
    fun playbackSpec(s: Session, itemId: String, version: MediaVersion, playSessionId: String, forceOriginal: Boolean = false): PlaybackSpec {
        require(!version.requiresOpening) { "该片源需要直播会话，目前仅支持服务器视频点播" }
        require(version.directPlay) { "服务器不允许该片源 Direct Play，请选择其他版本或外部播放器" }
        // DirectStreamUrl is the server's playback result. A CDN may legitimately use
        // master.m3u8 or "transcod" in its path; do not discard it based on its name.
        val direct = if(forceOriginal) null else version.directUrl.takeIf { it.isNotBlank() &&
            (version.transcodingUrl.isBlank() || resolveServerUrl(s.server, it) != resolveServerUrl(s.server, version.transcodingUrl)) }
            ?: version.path.takeIf { version.protocol in listOf("Http", "http", "1") && it.toHttpUrlOrNull() != null }
        var address = if (direct != null) resolveServerUrl(s.server, direct) else {
            val container = version.container.substringBefore(',').lowercase().filter { it.isLetterOrDigit() }.ifBlank { "mkv" }
            // Match PlaybackInfo's item ID; the selected version belongs in MediaSourceId.
            url(s.server, "Videos/$itemId/original.$container", mapOf(
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
            url(s.server, "Videos/$itemId/${version.id}/Subtitles/${stream.index}/Stream.${if (stream.codec in listOf("ass", "ssa")) "ass" else "srt"}").toString()
        return if (sameOrigin(s.server, address)) address.toHttpUrl().newBuilder().setQueryParameter("api_key", s.token).build().toString() else address
    }
    fun imageUrl(s: Session, item: VideoItem): String = url(s.server, "Items/${item.imageId}/Images/Primary", mapOf("maxWidth" to "360", "quality" to "85", "tag" to item.imageTag)).toString()
    suspend fun report(s: Session, event: String, itemId: String, spec: PlaybackSpec, positionMs: Long, paused: Boolean) {
        request(s.server, "Sessions/Playing${if (event.isBlank()) "" else "/$event"}", s.token, body = JSONObject()
            .put("ItemId", itemId).put("MediaSourceId", spec.version.id).put("PlaySessionId", spec.playSessionId)
            .put("PositionTicks", positionMs.coerceAtLeast(0) * 10_000).put("IsPaused", paused).put("CanSeek", true).put("PlayMethod", "DirectPlay"))
    }
}
