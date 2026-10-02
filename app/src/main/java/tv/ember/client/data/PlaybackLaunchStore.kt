package tv.ember.client.data

/** A single, short-lived launch handoff. URLs and tokens never go into intents or preferences. */
class PlaybackLaunchStore(private val clock: () -> Long = { System.nanoTime()/1_000_000 }) {
    private data class Entry(val session: Session, val item: VideoItem, val spec: PlaybackSpec, val at: Long)
    private var entry: Entry? = null
    @Synchronized fun put(session: Session, item: VideoItem, spec: PlaybackSpec) {
        entry=Entry(session,item,spec,clock())
    }
    @Synchronized fun take(session: Session, itemId: String, sourceId: String): Pair<VideoItem,PlaybackSpec>? {
        val cached=entry ?: return null
        entry=null
        return if(cached.session==session && cached.item.id==itemId && cached.spec.version.id==sourceId && clock()-cached.at in 0..30_000)
            cached.item to cached.spec else null
    }
    @Synchronized fun clear() { entry=null }
}
