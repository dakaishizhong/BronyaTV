package tv.ember.client.cache

import java.io.File
import java.security.MessageDigest

/** Call on an IO dispatcher. Names never contain server addresses, credentials or query values. */
class BoundedDiskStore(private val directory: File, private val capacity: () -> Long) {
    companion object {
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun namespace(server: String,user: String) = hash(server.trimEnd('/') + "\u0000" + user)
    }
    @Synchronized fun read(key: String): ByteArray? {
        if(capacity()<=0) return null
        val file=File(directory,hash(key))
        return runCatching { if(!file.isFile) null else file.readBytes().also { file.setLastModified(System.currentTimeMillis()) } }.getOrNull()
    }
    @Synchronized fun write(key: String,bytes: ByteArray) {
        val limit=capacity().coerceAtLeast(0)
        if(bytes.size>limit || limit==0L) return
        directory.mkdirs()
        val target=File(directory,hash(key));val temporary=File.createTempFile("entry-",".tmp",directory)
        try {
            temporary.outputStream().use { it.write(bytes) }
            check(temporary.renameTo(target)) { "Cache commit failed" }
            trim()
        } finally { temporary.delete() }
    }
    @Synchronized fun usedBytes()=directory.listFiles()?.filter { !it.name.endsWith(".tmp") }?.sumOf { it.length() } ?: 0L
    @Synchronized fun trim() {
        val files=directory.listFiles()?.filter { !it.name.endsWith(".tmp") }?.sortedBy { it.lastModified() } ?: return
        var bytes=files.sumOf { it.length() };val limit=capacity().coerceAtLeast(0)
        for(file in files) { if(bytes<=limit) break;val length=file.length();if(file.delete()) bytes-=length }
    }
    @Synchronized fun clear() { directory.listFiles()?.forEach { it.delete() } }
}
