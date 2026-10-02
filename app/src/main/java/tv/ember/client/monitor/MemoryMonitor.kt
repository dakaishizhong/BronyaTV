package tv.ember.client.monitor

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

data class MemorySample(val totalMb: Long, val availableMb: Long, val appMb: Long, val javaMb: Long)
class MemoryMonitor(context: Context) {
    private val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    fun sample(): MemorySample {
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val app = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val r = Runtime.getRuntime()
        return MemorySample(info.totalMem / 1_048_576, info.availMem / 1_048_576, app.totalPss / 1024L, (r.totalMemory() - r.freeMemory()) / 1_048_576)
    }
}
