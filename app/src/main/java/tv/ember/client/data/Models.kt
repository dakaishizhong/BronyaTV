package tv.ember.client.data

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import org.json.JSONArray
import org.json.JSONObject

data class Session(val server: String, val token: String, val userId: String, val userName: String)
data class VideoItem(
    val id: String, val name: String, val type: String, val overview: String = "",
    val year: String = "", val rating: String = "", val runtimeTicks: Long = 0,
    val resumeTicks: Long = 0, val imageTag: String = "", val imageId: String = id,
    val sources: List<MediaVersion> = emptyList(), val episodeLabel: String = "",
    val seriesId: String = "", val seasonId: String = "", val seasonNumber: Int = -1, val episodeNumber: Int = -1,
    val backdropTag: String = "", val backdropId: String = id, val communityRating: String = "",
    val genres: List<String> = emptyList(), val people: List<MediaPerson> = emptyList(),val collectionType: String=""
) {
    val isFolder get() = type in listOf("CollectionFolder", "Folder", "Series", "Season", "BoxSet")
    val subtitle get() = listOf(year, episodeLabel, rating, if (runtimeTicks >= 600_000_000) Tr.text(UiText.MIN_012 ,(runtimeTicks / 600_000_000)) else if(runtimeTicks > 0) Tr.text(UiText.SEC_013 ,(runtimeTicks / 10_000_000)) else "").filter { it.isNotBlank() }.joinToString("  ·  ")
    companion object {
        fun parse(o: JSONObject): VideoItem {
            val tag = o.optJSONObject("ImageTags")?.optString("Primary").orEmpty()
            val inherited = o.optString("SeriesPrimaryImageTag")
            return VideoItem(o.getString("Id"), o.optString("Name", Tr.text(UiText.UNTITLED_014)), o.optString("Type"),
                o.optString("Overview"), o.optString("ProductionYear").takeUnless { it == "null" }.orEmpty(),
                o.optString("OfficialRating"), o.optLong("RunTimeTicks"),
                o.optJSONObject("UserData")?.optLong("PlaybackPositionTicks") ?: 0,
                tag.ifBlank { inherited }, if (tag.isBlank() && inherited.isNotBlank()) o.optString("SeriesId", o.getString("Id")) else o.getString("Id"),
                o.optJSONArray("MediaSources").objects().map(MediaVersion::parse),
                if (o.optString("Type") == "Episode") "S${o.optInt("ParentIndexNumber")} E${o.optInt("IndexNumber")}" else "",
                o.optString("SeriesId"),o.optString("SeasonId"),o.optInt("ParentIndexNumber",-1),o.optInt("IndexNumber",-1),
                o.optJSONArray("BackdropImageTags")?.optString(0).orEmpty().ifBlank { o.optJSONArray("ParentBackdropImageTags")?.optString(0).orEmpty() },
                if((o.optJSONArray("BackdropImageTags")?.length() ?: 0)>0) o.getString("Id") else o.optString("ParentBackdropItemId",o.getString("Id")),
                o.optDouble("CommunityRating").takeIf { it.isFinite() && it>0 }?.let { "%.1f".format(java.util.Locale.ROOT,it) }.orEmpty(),
                o.optJSONArray("Genres").let { a -> if(a==null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter(String::isNotBlank) },
                o.optJSONArray("People").objects().map { MediaPerson(it.optString("Name"),it.optString("Type"),it.optString("Role"),it.optString("Id"),it.optString("PrimaryImageTag")) },o.optString("CollectionType"))
        }
    }
}
data class MediaPerson(val name: String,val type: String,val role: String,val id: String="",val imageTag: String="")
data class MediaStream(
    val index: Int, val type: String, val codec: String, val label: String, val language: String,
    val external: Boolean, val url: String, val default: Boolean, val width: Int, val height: Int,
    val bitrate: Long, val videoRange: String, val profile: String = "", val bitDepth: Int = 0,
    val frameRate: Double = 0.0, val channels: Int = 0, val sampleRate: Int = 0
) {
    companion object {
        fun parse(o: JSONObject) = MediaStream(o.optInt("Index"), o.optString("Type"), o.optString("Codec"),
            o.optString("DisplayTitle").ifBlank { o.optString("Title").ifBlank { "${o.optString("Language")} ${o.optString("Codec")}" } },
            o.optString("Language"), o.optBoolean("IsExternal"), o.optString("DeliveryUrl"),
            o.optBoolean("IsDefault"), o.optInt("Width"), o.optInt("Height"), o.optLong("BitRate"),
            o.optString("VideoRange").ifBlank { o.optString("ExtendedVideoType") }, o.optString("Profile"), o.optInt("BitDepth"),
            o.optDouble("AverageFrameRate", 0.0).takeIf { it.isFinite() && it > 0 } ?: o.optDouble("RealFrameRate", 0.0),
            o.optInt("Channels"), o.optInt("SampleRate"))
    }
}
data class MediaVersion(
    val id: String, val name: String, val container: String, val path: String,
    val directUrl: String, val directPlay: Boolean, val bitrate: Long,
    val streams: List<MediaStream>, val headers: Map<String, String>, val itemId: String,
    val requiresOpening: Boolean, val addApiKeyToDirectUrl: Boolean = false, val protocol: String = "",
    val transcodingUrl: String = "", val sizeBytes: Long = 0
) {
    val label: String get() {
        val v = streams.firstOrNull { it.type == "Video" }
        return listOf(name, v?.let { if (it.height > 0) "${it.height}P" else "" }.orEmpty(),
            v?.videoRange?.takeUnless { it in listOf("", "SDR", "None") }.orEmpty(), container.uppercase(),
            if (bitrate > 0) "%.1f Mbps".format(bitrate / 1_000_000.0) else "").filter { it.isNotBlank() }.joinToString(" · ")
    }
    companion object {
        fun parse(o: JSONObject): MediaVersion {
            val h = o.optJSONObject("RequiredHttpHeaders")
            return MediaVersion(o.getString("Id"), o.optString("Name", Tr.text(UiText.ORIGINAL_SOURCE_015)), o.optString("Container", "mkv"),
                o.optString("Path"), o.optString("DirectStreamUrl"), o.optBoolean("SupportsDirectPlay", true),
                o.optLong("Bitrate"), o.optJSONArray("MediaStreams").objects().map(MediaStream::parse),
                h?.keys()?.asSequence()?.associateWith { h.getString(it) }.orEmpty(), o.optString("ItemId"), o.optBoolean("RequiresOpening"),
                o.optBoolean("AddApiKeyToDirectStreamUrl"), o.optString("Protocol"), o.optString("TranscodingUrl"), o.optLong("Size"))
        }
    }
}
data class PlaybackInfo(val versions: List<MediaVersion>, val playSessionId: String)
data class PlaybackSpec(val url: String, val headers: Map<String, String>, val version: MediaVersion, val playSessionId: String)
data class ItemPage(val items: List<VideoItem>, val total: Int)
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
