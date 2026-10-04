package tv.ember.client.cache

import kotlinx.coroutines.*

/** Share identical work, but cancel it once every observing screen has gone away. */
class SharedRequests<K,V>(private val scope: CoroutineScope) {
    private class Entry<V>(val job: Deferred<V>,var observers: Int=0)
    private val pending=mutableMapOf<K,Entry<V>>()
    suspend fun get(key: K,work: suspend ()->V): V {
        val entry=synchronized(pending) {
            (pending[key] ?: Entry(scope.async(start=CoroutineStart.LAZY) { work() }).also { created ->
                pending[key]=created
                created.job.invokeOnCompletion { synchronized(pending) { if(pending[key]===created) pending.remove(key) } }
            }).also { it.observers++ }
        }
        try { return entry.job.await() }
        finally { synchronized(pending) {
            entry.observers--
            if(entry.observers==0 && !entry.job.isCompleted) { if(pending[key]===entry) pending.remove(key);entry.job.cancel() }
        } }
    }
    fun cancel() { synchronized(pending) { val entries=pending.values.toList();pending.clear();entries.forEach { it.job.cancel() } } }
}
