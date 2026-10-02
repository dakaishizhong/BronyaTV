package tv.ember.client.monitor

/** Reads actual received bytes. Rates include idle time; they are not a speed test. */
class TransferWindow(private val clock: () -> Long) {
    data class Sample(val rate: Long, val average: Long, val peak: Long, val total: Long, val idleMs: Long?)
    private data class Interval(val end: Long, val duration: Long, val bytes: Long)
    private val intervals = ArrayDeque<Interval>()
    private var last = clock()
    private var pending = 0L
    private var total = 0L
    private var lastByte: Long? = null
    @Synchronized fun add(bytes: Int) {
        if(bytes <= 0) return
        pending += bytes; total += bytes; lastByte = clock()
    }
    @Synchronized fun sample(): Sample {
        val now=clock(); val elapsed=(now-last).coerceAtLeast(1)
        val count=pending;pending=0;last=now
        intervals.addLast(Interval(now,elapsed,count))
        while(intervals.isNotEmpty() && now-intervals.first().end >= 30_000) intervals.removeFirst()
        val recent=intervals.filter { now-it.end < 5000 }
        val avg=recent.sumOf { it.bytes }*1000/recent.sumOf { it.duration }.coerceAtLeast(1)
        val peak=intervals.filter { it.duration >= 250 }.maxOfOrNull { it.bytes*1000/it.duration } ?: 0
        return Sample(count*1000/elapsed,avg,peak,total,lastByte?.let { (now-it).coerceAtLeast(0) })
    }
}
