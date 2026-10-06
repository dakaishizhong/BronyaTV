package tv.ember.client.cache

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import androidx.media3.common.util.UnstableApi
import tv.ember.client.network.RangeChunkStore
import tv.ember.client.network.RangeIdentity
import tv.ember.client.network.RangePlaybackStatus
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/** Storage half of the shared range pipeline. This class never owns an HTTP downloader. */
@UnstableApi
class DiskPrefetcher(val handle: PlaybackDiskCache.Handle, val key: String, private val range: RangePlaybackStatus) : RangeChunkStore, AutoCloseable {
    private data class Write(val position: Long, val bytes: ByteArray, val generation: Long)
    private val lock=Any()
    private val queue=ArrayBlockingQueue<Write>(2)
    private val queuedBytes=AtomicLong()
    private val queuedBodies=AtomicInteger()
    @Volatile private var closed=false
    @Volatile private var failed=false
    @Volatile private var invalidating=false
    @Volatile private var cursor=0L
    @Volatile private var generation=0L
    @Volatile override var identity: RangeIdentity?=null; private set
    @Volatile private var mediaPosition=0L
    @Volatile private var playbackBufferedBytes=0L
    private val storageLock=Any()
    private val readLock=Any()
    @Volatile private var readInput:RandomAccessFile?=null
    private var readSpan:androidx.media3.datasource.cache.CacheSpan?=null
    private var readGeneration=-1L
    val hitBytes=AtomicLong()
    val writtenBytes=AtomicLong()
    val skippedWrites=AtomicLong()
    val rangeStatus get()=range
    val capacityBytes get()=handle.plan.capacityBytes
    override val aheadBytes get()=handle.plan.aheadBytes
    override val enabled get()=!closed && !invalidating
    override val canPrefetch get()=enabled && !failed
    val pendingWriteBytes get()=queuedBytes.get()
    val usedBytes get()=handle.cache.cacheSpace
    val cachedAheadBytes: Long get()=if(closed) 0 else runCatching { handle.cache.getCachedLength(key,cursor,aheadBytes).coerceAtLeast(0) }.getOrDefault(0)
    @Volatile var state=Tr.text(UiText.SHARED_RANGE_PIPELINE); private set
    private val worker=Thread(::run,"BronyaTVDiskWriter").apply { isDaemon=true;start() }

    fun seek(position: Long)=synchronized(lock) {
        if(closed) return@synchronized
        if(position!=cursor) { generation++;clearQueue();if(invalidating) queue.offer(Write(-1,ByteArray(0),generation)) }
        mediaPosition=position;cursor=position;playbackBufferedBytes=0
        handle.evictor?.setPlaybackWindow(handle.cache,key,cursor)
        range.reader?.setPlaybackPosition(cursor)
    }
    fun advance(position: Long) {
        mediaPosition=position;cursor=(position-playbackBufferedBytes).coerceAtLeast(0)
        handle.evictor?.setPlaybackWindow(handle.cache,key,cursor)
        range.reader?.setPlaybackPosition(cursor)
    }
    fun updatePlaybackBuffer(bytes:Long) {
        playbackBufferedBytes=bytes.coerceAtLeast(0);cursor=(mediaPosition-playbackBufferedBytes).coerceAtLeast(0)
        handle.evictor?.setPlaybackWindow(handle.cache,key,cursor)
        range.reader?.setPlaybackPosition(cursor)
    }
    override fun cachedLength(position:Long,length:Long):Long = if(enabled)
        runCatching { handle.cache.getCachedLength(key,position,length) }.getOrDefault(-length) else -length
    override fun validate(identity: RangeIdentity): RangeChunkStore {
        synchronized(lock) {
            if(this.identity!=null && (this.identity!=identity || (identity.etag==null || identity.etag.startsWith("W/",true)) && identity.lastModified==null)) {
                generation++;clearQueue();invalidating=true
                // Index invalidation is ordered with writes, but never holds up the first network bytes.
                queue.offer(Write(-1,ByteArray(0),generation))
            }
            this.identity=identity
            val epoch=generation
            return object: RangeChunkStore {
                override val identity get()=if(enabled) this@DiskPrefetcher.identity else null
                override fun cachedLength(position:Long,length:Long)=if(enabled) this@DiskPrefetcher.cachedLength(position,length) else -length
                override val aheadBytes get()=this@DiskPrefetcher.aheadBytes
                override val enabled get()=this@DiskPrefetcher.enabled && epoch==generation
                override val canPrefetch get()=enabled && this@DiskPrefetcher.canPrefetch
                override fun validate(identity:RangeIdentity)=this@DiskPrefetcher.validate(identity)
                override fun contains(position:Long,length:Int)=enabled && this@DiskPrefetcher.contains(position,length)
                override fun read(position:Long,target:ByteArray,offset:Int,length:Int)=if(enabled) readForEpoch(position,target,offset,length,epoch) else -1
                override fun offer(position:Long,bytes:ByteArray)=offerForEpoch(position,bytes,epoch)
                override fun persist(position:Long,bytes:ByteArray)=write(Write(position,bytes,epoch))
            }
        }
    }
    override fun contains(position: Long,length: Int): Boolean = enabled &&
        runCatching { handle.cache.isCached(key,position,length.toLong()) }.getOrDefault(false)
    override fun read(position:Long,target:ByteArray,offset:Int,length:Int)=readForEpoch(position,target,offset,length,generation)
    private fun readForEpoch(position:Long,target:ByteArray,offset:Int,length:Int,epoch:Long):Int = synchronized(readLock) {
        if(!enabled || epoch!=generation) return@synchronized -1
        try {
            var span=readSpan
            if(span==null || readGeneration!=epoch || position<span.position || position>=span.position+span.length) {
                readInput?.close();readInput=null;readSpan=null
                span=handle.cache.startReadWriteNonBlocking(key,position,length.toLong()) ?: return@synchronized -1
                if(!span.isCached) { handle.cache.releaseHoleSpan(span);return@synchronized -1 }
                readInput=RandomAccessFile(requireNotNull(span.file),"r");readSpan=span;readGeneration=epoch
            }
            val count=minOf(length.toLong(),span.position+span.length-position).toInt()
            val input=requireNotNull(readInput)
            val fileOffset=position-span.position
            if(input.filePointer!=fileOffset) input.seek(fileOffset)
            input.readFully(target,offset,count)
            hitBytes.addAndGet(count.toLong());count
        } catch(e:Exception) {
            runCatching { readInput?.close() };readInput=null;readSpan=null;-1
        }
    }
    override fun offer(position: Long,bytes: ByteArray)=offerForEpoch(position,bytes,generation)
    private fun offerForEpoch(position:Long,bytes:ByteArray,epoch:Long) {
        // At most two bodies including the currently writing body, never wait on disk IO.
        synchronized(lock) {
            if(!canPrefetch || epoch!=generation || queuedBodies.get()>=2 || queuedBytes.get()+bytes.size>2L*range.chunkBytes) { skippedWrites.incrementAndGet();return }
            queuedBytes.addAndGet(bytes.size.toLong());queuedBodies.incrementAndGet()
            if(!queue.offer(Write(position,bytes.copyOf(),epoch))) { queuedBytes.addAndGet(-bytes.size.toLong());queuedBodies.decrementAndGet() }
        }
    }
    override fun persist(position: Long,bytes: ByteArray): Boolean = write(Write(position,bytes,generation))
    private fun write(task: Write): Boolean {
        if(task.position<0) {
            synchronized(storageLock) {
                if(task.generation==generation && !closed) {
                    try { handle.cache.removeResource(key);invalidating=false }
                    catch(e:Exception) { failed=true;state=Tr.text(UiText.DISK_UNAVAILABLE_USING_MEMORY_BUFFER_011) }
                }
            }
            return false
        }
        if(!canPrefetch || task.generation!=generation) return false
        if(handle.directory.usableSpace<64*DiskCachePlan.MIB) { failed=true;state=Tr.text(UiText.LOW_DISK_SPACE_READ_AHEAD_STOPPED_002);return false }
        return try {
            // Only writers serialize; foreground cached reads and network reads never acquire this lock.
            synchronized(storageLock) {
                if(!enabled || task.generation!=generation) return false
                var offset=0
                // A seek range may straddle cached spans and holes. Commit only missing portions;
                // a cached first span does not mean the entire body has reached disk.
                while(offset<task.bytes.size) {
                    if(!enabled || task.generation!=generation) return false
                    val position=task.position+offset
                    val remaining=task.bytes.size-offset
                    val span=handle.cache.startReadWriteNonBlocking(key,position,remaining.toLong()) ?: return false
                    if(span.isCached) {
                        offset+=minOf(remaining.toLong(),span.position+span.length-position).toInt()
                        continue
                    }
                    try {
                        val count=if(span.isOpenEnded) remaining else minOf(remaining.toLong(),span.length).toInt()
                        val file=handle.cache.startFile(key,position,count.toLong())
                        try {
                            file.outputStream().use { it.write(task.bytes,offset,count) }
                            if(task.generation!=generation || closed) { file.delete();return false }
                            handle.cache.commitFile(file,count.toLong())
                            writtenBytes.addAndGet(count.toLong());offset+=count
                            state=Tr.text(UiText.SHARED_RANGE_DISK_READY)
                        } catch(e:Exception) { file.delete();throw e }
                    } finally { handle.cache.releaseHoleSpan(span) }
                }
            }
            true
        } catch(e:Exception) { failed=true;state=Tr.text(UiText.DISK_UNAVAILABLE_USING_MEMORY_BUFFER_011);false }
    }
    private fun clearQueue() {
        while(true) { val task=queue.poll() ?: break;queuedBytes.addAndGet(-task.bytes.size.toLong());if(task.position>=0) queuedBodies.decrementAndGet() }
    }
    private fun run() {
        while(!closed) {
            try {
                val task=queue.take()
                try { write(task) } finally { queuedBytes.addAndGet(-task.bytes.size.toLong());if(task.position>=0) queuedBodies.decrementAndGet() }
            } catch(e:InterruptedException) { if(closed) return }
        }
    }
    override fun close() {
        synchronized(lock) { if(closed) return;closed=true;generation++;clearQueue() }
        worker.interrupt();runCatching { readInput?.close() };readInput=null
    }
}
