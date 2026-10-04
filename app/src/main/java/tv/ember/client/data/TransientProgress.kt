package tv.ember.client.data

import tv.ember.client.cache.BoundedDiskStore

/** Bridges the stop-report/server-refresh race. Never persisted; server user data remains authoritative. */
class TransientProgress(private val now: ()->Long={ System.nanoTime()/1_000_000 }) {
    private data class Entry(val ticks: Long,val ended: Boolean,val at: Long)
    private val values=LinkedHashMap<String,Entry>()
    private fun key(s: Session,id: String)=BoundedDiskStore.namespace(s.server,s.userId)+":"+id
    @Synchronized fun update(s: Session,id: String,positionMs: Long,ended: Boolean) {
        values[key(s,id)]=Entry(positionMs.coerceAtLeast(0)*10000,ended,now())
        while(values.size>128) values.remove(values.keys.first())
    }
    @Synchronized fun apply(s: Session,item: VideoItem): VideoItem {
        val entry=values[key(s,item.id)]?.takeIf { now()-it.at<30_000 } ?: return item
        return item.copy(resumeTicks=if(entry.ended) 0 else entry.ticks)
    }
    @Synchronized fun finished(s: Session,id: String)=values[key(s,id)]?.let { it.ended && now()-it.at<30_000 } ?: false
    @Synchronized fun clear() { values.clear() }
}
