package tv.ember.client.player

object SkipPolicy {
    fun intro(position: Long,duration: Long,seconds: Int): Long? {
        val target=seconds.coerceIn(0,600)*1000L
        return target.takeIf { it>0 && duration>it && position<it }
    }
    fun outro(position: Long,duration: Long,seconds: Int): Boolean {
        val tail=seconds.coerceIn(0,600)*1000L
        return tail>0 && duration>tail && position>=duration-tail && position<duration
    }
}
