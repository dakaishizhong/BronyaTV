package tv.ember.client

import tv.ember.client.network.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

internal interface RangeBenchmarkStore:RangeChunkStore,AutoCloseable { val pendingCopyBytes:Long }

/** Real disk IO with bounded foreground copies and representation-bound storage leases. */
internal class FileRangeStore(private val chunk:Int, override val aheadBytes:Long):RangeBenchmarkStore {
    private val folder=Files.createTempDirectory("bronya-range-").toFile()
    private data class Entry(val position:Long,val length:Int,val file:File)
    private val entries=ConcurrentSkipListMap<Long,Entry>()
    private val writer=Executors.newSingleThreadExecutor()
    private val copies=java.util.concurrent.Semaphore(2)
    private var identity:RangeIdentity?=null
    @Volatile private var generation=0L
    val hitBytes=AtomicLong()
    val pendingBytes=AtomicLong()
    override val pendingCopyBytes get()=pendingBytes.get()
    @Volatile private var closed=false
    override val enabled get()=!closed
    override fun validate(identity:RangeIdentity):RangeChunkStore {
        if(this.identity!=null && (this.identity!=identity || identity.etag==null && identity.lastModified==null)) drain()
        synchronized(this) {
            if(this.identity!=null && (this.identity!=identity || identity.etag==null && identity.lastModified==null)) {
                generation++;entries.values.forEach { it.file.delete() };entries.clear()
            }
            this.identity=identity
            val epoch=generation
            return object:RangeChunkStore {
                override val aheadBytes get()=this@FileRangeStore.aheadBytes
                override val enabled get()=this@FileRangeStore.enabled && epoch==generation
                override fun validate(identity:RangeIdentity)=this@FileRangeStore.validate(identity)
                override fun contains(position:Long,length:Int)=enabled && this@FileRangeStore.contains(position,length)
                override fun read(position:Long,target:ByteArray,offset:Int,length:Int)=if(enabled) this@FileRangeStore.read(position,target,offset,length) else -1
                override fun offer(position:Long,bytes:ByteArray)=offerForEpoch(position,bytes,epoch)
                override fun persist(position:Long,bytes:ByteArray)=persistForEpoch(position,bytes,epoch)
            }
        }
    }
    override fun contains(position:Long,length:Int):Boolean {
        var cursor=position
        while(cursor<position+length) {
            val end=entries.headMap(cursor,true).values.maxOfOrNull { it.position+it.length } ?: return false
            if(end<=cursor) return false
            cursor=end
        }
        return true
    }
    override fun read(position:Long,target:ByteArray,offset:Int,length:Int):Int {
        val entry=entries.headMap(position,true).values.filter { it.position+it.length>position }.maxByOrNull { it.position+it.length } ?: return -1
        val count=minOf(length.toLong(),entry.position+entry.length-position).toInt()
        java.io.RandomAccessFile(entry.file,"r").use { it.seek(position-entry.position);it.readFully(target,offset,count) }
        hitBytes.addAndGet(count.toLong());return count
    }
    override fun offer(position:Long,bytes:ByteArray)=offerForEpoch(position,bytes,generation)
    private fun offerForEpoch(position:Long,bytes:ByteArray,epoch:Long) {
        if(!enabled || epoch!=generation || !copies.tryAcquire()) return
        val copy=bytes.copyOf();pendingBytes.addAndGet(copy.size.toLong())
        writer.execute { try { persistForEpoch(position,copy,epoch) } finally { pendingBytes.addAndGet(-copy.size.toLong());copies.release() } }
    }
    override fun persist(position:Long,bytes:ByteArray)=persistForEpoch(position,bytes,generation)
    @Synchronized private fun persistForEpoch(position:Long,bytes:ByteArray,epoch:Long):Boolean {
        if(!enabled || epoch!=generation) return false
        val file=File(folder,"$position-${bytes.size}");file.writeBytes(bytes)
        entries[position]=Entry(position,bytes.size,file);return true
    }
    fun drain() { writer.submit {}.get(5,TimeUnit.SECONDS) }
    override fun close() { drain();closed=true;writer.shutdownNow();folder.deleteRecursively() }
}
