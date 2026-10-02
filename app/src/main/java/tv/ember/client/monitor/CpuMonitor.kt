package tv.ember.client.monitor

import android.os.Process
import android.os.SystemClock
import java.io.File

data class CpuSample(val percent: Double?, val processOnly: Boolean, val frequencyMhz: Long?, val coreEquivalent: Double)
class CpuMonitor {
    private var previousTotal = 0L
    private var previousIdle = 0L
    private var previousApp = Process.getElapsedCpuTime()
    private var previousTime = SystemClock.elapsedRealtime()
    fun sample(): CpuSample {
        var processOnly = false
        val now=SystemClock.elapsedRealtime();val app=Process.getElapsedCpuTime()
        val singleCore=if(now>previousTime) 100.0*(app-previousApp)/(now-previousTime) else 0.0
        previousApp=app;previousTime=now
        val percent = runCatching {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() }
            val values = line.trim().split(Regex("\\s+")).drop(1).take(8).map(String::toLong)
            val total = values.sum(); val idle = values[3] + values.getOrElse(4) { 0 }
            val result = if (previousTotal > 0 && total > previousTotal) 100.0 * (1 - (idle - previousIdle).toDouble() / (total - previousTotal)) else null
            previousTotal = total; previousIdle = idle
            result
        }.getOrElse {
            processOnly = true
            singleCore/Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        }
        val frequency = runCatching {
            (File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").readText().trim().toLong() / 1000).takeIf { it > 0 }
        }.getOrNull()
        return CpuSample(percent?.coerceIn(0.0, 100.0), processOnly, frequency,singleCore.coerceAtLeast(0.0))
    }
}
