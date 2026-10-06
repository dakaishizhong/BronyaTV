package tv.ember.client.cache

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
class PlaybackDiskCache(context: Context) {
    private val context = context.applicationContext
    private val directory = File(context.cacheDir, "playback-v1")
    private var evictor: PlaybackCacheEvictor? = null
    private var cache: SimpleCache? = null
    @Volatile var mode = Tr.text(UiText.NOT_STARTED_007); private set
    @Volatile var capacityBytes = 0L; private set
    data class Handle(val cache: SimpleCache, val plan: DiskCachePlan, val directory: File, val evictor:PlaybackCacheEvictor?=null)
    data class Snapshot(val usedBytes: Long, val capacityBytes: Long, val availableBytes: Long)

    /** May read the disk/cache index. Call on an IO thread. */
    @Synchronized fun configure(requestedMb: Int, bitrate: Long, aheadSeconds: Int): Handle? {
        try {
            val existing = cache?.cacheSpace ?: storedBytes()
            val plan = DiskCachePlan.create(requestedMb, context.cacheDir.usableSpace, existing, bitrate, aheadSeconds)
            capacityBytes = plan.capacityBytes
            if (!plan.enabled) {
                cache?.let { evictor?.resize(it, 0) }
                mode = if (requestedMb == 0) Tr.text(UiText.DISABLED_008) else Tr.text(UiText.LOW_STORAGE_USING_MEMORY_BUFFER_009)
                return null
            }
            val current = cache ?: run {
                val policy = PlaybackCacheEvictor(plan.capacityBytes)
                val created = SimpleCache(directory, policy, StandaloneDatabaseProvider(context))
                try { created.checkInitialization() } catch (e: Exception) { created.release(); throw e }
                evictor = policy; cache = created; created
            }
            evictor?.resize(current, plan.capacityBytes)
            mode = Tr.text(UiText.DISK_READ_AHEAD_CACHE_010)
            return Handle(current, plan, directory,evictor)
        } catch (e: Exception) {
            mode = Tr.text(UiText.DISK_UNAVAILABLE_USING_MEMORY_BUFFER_011); capacityBytes = 0
            android.util.Log.w("BronyaTVCache", "Disk cache unavailable", e)
            return null
        }
    }
    @Synchronized fun snapshot(): Snapshot {
        val used = cache?.cacheSpace ?: storedBytes()
        return Snapshot(used, capacityBytes, context.cacheDir.usableSpace)
    }
    @Synchronized fun clear() {
        val current = cache
        if (current != null) current.keys.toList().forEach(current::removeResource)
        else SimpleCache.delete(directory, StandaloneDatabaseProvider(context))
    }
    private fun storedBytes() = if(directory.exists()) directory.walkTopDown().filter(File::isFile).sumOf { it.length() } else 0L
}
