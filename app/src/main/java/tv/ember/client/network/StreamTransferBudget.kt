package tv.ember.client.network

import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** One budget for all foreground and disk read-ahead HTTP requests, including single-stream fallback. */
class StreamTransferBudget(val limit: Int) {
    init { require(limit in 1..8) }
    private val lock = Object()
    private val active = mutableSetOf<Lease>()
    private var foregroundWaiting = 0
    private var peak = 0
    @Volatile var foregroundDemand = limit
    val foregroundActive get() = synchronized(lock) { active.count { !it.background } }
    val backgroundActive get() = synchronized(lock) { active.count { it.background } }
    val activeCount get() = synchronized(lock) { active.size }
    val peakCount get() = synchronized(lock) { peak }
    private inner class Lease(val background: Boolean, val cancel: () -> Unit) : AutoCloseable {
        private val released = AtomicBoolean()
        override fun close() {
            if (released.compareAndSet(false, true)) synchronized(lock) { active.remove(this); lock.notifyAll() }
        }
    }
    private fun acquire(background: Boolean, cancelled: () -> Boolean, cancel: () -> Unit): Lease {
        synchronized(lock) { if (!background) foregroundWaiting++ }
        try {
            while (true) {
                synchronized(lock) {
                    if (cancelled()) throw InterruptedIOException("Stream request cancelled")
                    if (active.size < limit && (!background || (foregroundWaiting == 0 && active.count { it.background } < (limit-foregroundDemand).coerceAtLeast(0)))) {
                        return Lease(background, cancel).also { active.add(it); peak = maxOf(peak, active.size) }
                    }
                    lock.wait(50)
                }
                // Demand only gates new background leases; an in-flight body completes naturally.
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw InterruptedIOException("Stream request interrupted")
        } finally {
            synchronized(lock) { if (!background) foregroundWaiting--; lock.notifyAll() }
        }
    }
    fun interceptor(background: Boolean = false) = Interceptor { chain ->
        val call = chain.call()
        val lease = acquire(background, call::isCanceled, call::cancel)
        try {
            val response = chain.proceed(chain.request())
            val body = response.body
            if (body == null) { lease.close(); response }
            else response.newBuilder().body(object : ResponseBody() {
                private val input = object : ForwardingSource(body.source()) {
                    override fun close() { try { super.close() } finally { lease.close() } }
                }.buffer()
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = input
            }).build()
        } catch (e: Exception) { lease.close(); throw e }
    }
}
