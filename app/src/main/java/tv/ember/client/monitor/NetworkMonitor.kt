package tv.ember.client.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File

data class NetworkSample(val bytesPerSecond: Long, val state: String, val connected: Boolean,
                         val average:Long, val peak:Long, val total:Long, val idleMs:Long?)
class NetworkMonitor(context: Context) : TransferListener {
    private val context=context.applicationContext
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val window=TransferWindow(SystemClock::elapsedRealtime)
    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) { if(isNetwork) window.add(bytesTransferred) }
    fun sample(): NetworkSample {
        val s=window.sample()
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val kind = when {
            caps == null -> "无网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            else -> "已连接"
        }
        val state = if(caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "$kind · 未验证互联网" else kind
        return NetworkSample(s.rate,state,connected,s.average,s.peak,s.total,s.idleMs)
    }
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun linkDetails():String {
        val active=manager.activeNetwork
        val caps=manager.getNetworkCapabilities(active)
        val iface=manager.getLinkProperties(active)?.interfaceName ?: "未提供"
        val wifi=if(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true) {
            (if(Build.VERSION.SDK_INT>=29) caps.transportInfo as? WifiInfo else null)
                ?: (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
        } else null
        val physical=if(wifi!=null) "Wi-Fi 链路 ${wifi.linkSpeed}Mbps · ${wifi.frequency}MHz · 信号 ${wifi.rssi}dBm" else {
            val speed=if(caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true && iface.matches(Regex("[a-zA-Z0-9_.-]+")))
                runCatching { File("/sys/class/net/$iface/speed").readText().trim().toInt() }.getOrNull()?.takeIf { it>0 } else null
            "以太网链路 ${speed?.let { "${it}Mbps" } ?: "系统未提供"}"
        }
        val kernel=listOf("ipv4/tcp_rmem","core/rmem_max","ipv4/tcp_moderate_rcvbuf","ipv4/tcp_window_scaling").joinToString("\n") { key ->
            val value=runCatching { File("/proc/sys/net/$key").readText().trim().replace(Regex("\\s+")," ") }.getOrNull()
            "${key.substringAfter('/')} ${value ?: "系统禁止读取/未提供"}"
        }
        return "接口 $iface\n$physical（链路速率不是下载速率）\n$kernel"
    }
}
