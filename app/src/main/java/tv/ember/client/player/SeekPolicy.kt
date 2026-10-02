package tv.ember.client.player

object SeekPolicy {
    fun parseTime(input: String): Long? {
        val parts=input.trim().split(':')
        if(parts.size !in 2..3 || parts.any { it.isEmpty() || it.any { char -> char !in '0'..'9' } }) return null
        val numbers=parts.map { it.toLongOrNull() ?: return null }
        if(numbers.last()>=60 || (parts.size==3 && numbers[1]>=60)) return null
        val seconds=if(parts.size==2) {
            if(numbers[0]>999_999) return null
            numbers[0]*60+numbers[1]
        } else {
            if(numbers[0]>9999) return null
            numbers[0]*3600+numbers[1]*60+numbers[2]
        }
        return seconds*1000
    }
    fun target(position: Long, duration: Long, direction: Int, repeat: Int, baseSeconds: Int): Long {
        require(direction==-1 || direction==1)
        val seconds=(baseSeconds.coerceIn(5,60)*when { repeat>=12 -> 6;repeat>=5 -> 3;else -> 1 }).coerceAtMost(60)
        val delta=seconds*1000L*direction
        val next=if(delta>0 && position>Long.MAX_VALUE-delta) Long.MAX_VALUE else (position+delta).coerceAtLeast(0)
        return if(duration>0) next.coerceAtMost((duration-1).coerceAtLeast(0)) else next
    }
    fun time(ms: Long): String {
        val total=ms.coerceAtLeast(0)/1000
        return if(total>=3600) "%d:%02d:%02d".format(total/3600,total/60%60,total%60) else "%02d:%02d".format(total/60,total%60)
    }
}
