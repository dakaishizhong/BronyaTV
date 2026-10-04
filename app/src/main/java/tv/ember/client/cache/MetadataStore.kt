package tv.ember.client.cache

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import tv.ember.client.data.Session

/** Persistent descriptive metadata only. User progress and negotiated playback data are never written. */
class MetadataStore(directory: java.io.File) {
    private val disk=BoundedDiskStore(directory) { 24L*1024*1024 }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val requests=Semaphore(4)
    private val pending=SharedRequests<String,String>(scope)
    fun key(s: Session,path: String,query: Map<String,String>) = BoundedDiskStore.namespace(s.server,s.userId)+":"+path+":"+query.toSortedMap()
    suspend fun cached(key: String): String?=withContext(Dispatchers.IO) { disk.read(key)?.toString(Charsets.UTF_8) }
    suspend fun fresh(key: String,request: suspend ()->String): String {
        return pending.get(key) { requests.withPermit { request().also { raw -> runCatching { disk.write(key,sanitize(raw).toByteArray()) } } } }
    }
    companion object {
        private val fields=setOf("Id","Name","Type","Overview","ProductionYear","OfficialRating","RunTimeTicks","ImageTags","Primary","BackdropImageTags","ParentBackdropImageTags","ParentBackdropItemId","SeriesPrimaryImageTag","SeriesId","SeasonId","ParentIndexNumber","IndexNumber","MediaSources","Container","Bitrate","Size","MediaStreams","Index","Codec","DisplayTitle","Title","Language","IsExternal","IsDefault","Width","Height","BitRate","VideoRange","ExtendedVideoType","Profile","BitDepth","AverageFrameRate","RealFrameRate","Channels","SampleRate","CommunityRating","Genres","People","Role","PrimaryImageTag","Items","TotalRecordCount","CollectionType")
        fun sanitize(raw: String): String {
            fun clean(value: Any?): Any?=when(value) {
                is JSONObject -> JSONObject().apply { value.keys().asSequence().filter { it in fields }.forEach { put(it,clean(value.opt(it))) } }
                is JSONArray -> JSONArray().apply { for(i in 0 until value.length()) put(clean(value.opt(i))) }
                else -> value
            }
            val value=if(raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw)
            return clean(value).toString()
        }
    }
}
