package tv.ember.client.monitor

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
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
                         val average:Long, val peak:Long, val total:Long, val idleMs:Long?,val average30:Long=0)
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
            caps == null -> Tr.text(UiText.NO_NETWORK_032)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Tr.text(UiText.ETHERNET_033)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            else -> Tr.text(UiText.CONNECTED_034)
        }
        val state = if(caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) Tr.text(UiText.INTERNET_NOT_VERIFIED_035 ,(kind)) else kind
        return NetworkSample(s.rate,state,connected,s.average,s.peak,s.total,s.idleMs,s.average30)
    }
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun linkDetails():String {
        val active=manager.activeNetwork
        val caps=manager.getNetworkCapabilities(active)
        val iface=manager.getLinkProperties(active)?.interfaceName ?: Tr.text(UiText.NOT_PROVIDED_026)
        val wifi=if(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true) {
            (if(Build.VERSION.SDK_INT>=29) caps.transportInfo as? WifiInfo else null)
                ?: (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
        } else null
        val physical=if(wifi!=null) Tr.text(UiText.WI_FI_LINK_MBPS_MHZ_SIGNAL_036 ,(wifi.linkSpeed),(wifi.frequency),(wifi.rssi)) else {
            val speed=if(caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true && iface.matches(Regex("[a-zA-Z0-9_.-]+")))
                runCatching { File("/sys/class/net/${iface}/speed").readText().trim().toInt() }.getOrNull()?.takeIf { it>0 } else null
            Tr.text(UiText.ETHERNET_LINK_038 ,(speed?.let { "${it}Mbps" } ?: Tr.text(UiText.NOT_REPORTED_BY_SYSTEM_037)))
        }
        val kernel=listOf("ipv4/tcp_rmem","core/rmem_max","ipv4/tcp_moderate_rcvbuf","ipv4/tcp_window_scaling").joinToString("\n") { key ->
            val value=runCatching { File("/proc/sys/net/${key}").readText().trim().replace(Regex("\\s+")," ") }.getOrNull()
            "${key.substringAfter('/')} ${value ?: Tr.text(UiText.RESTRICTED_OR_NOT_REPORTED_039)}"
        }
        return Tr.text(UiText.INTERFACE_LINK_SPEED_IS_NOT_DOWNLOAD_040 ,(iface),(physical),(kernel))
    }
}
