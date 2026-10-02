package tv.ember.client.monitor

import android.os.SystemClock
import okhttp3.*
import tv.ember.client.network.ReceiveBufferSocketFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Timings of real playback requests, never a ping/RTT estimate. */
class HttpTransportMonitor(private val sockets: ReceiveBufferSocketFactory? = null,
                           private val clock: () -> Long = SystemClock::elapsedRealtime) : EventListener.Factory {
    data class Snapshot(val protocol: String = "等待连接", val remote: String = "未提供", val dnsMs: Long? = null,
                        val connectMs: Long? = null, val tlsMs: Long? = null, val ttfbMs: Long? = null,
                        val contentType: String = "未提供", val range: String = "未提供", val status: Int = 0)
    @Volatile var latest=Snapshot(); private set
    val requests=AtomicInteger()
    val redirects=AtomicInteger()
    val failures=AtomicInteger()
    val activeRequests=AtomicInteger()
    val peakConnections=AtomicInteger()
    private val liveConnections=java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Connection,Boolean>())
    private fun recordPeak(value:Int) {
        while(true) { val old=peakConnections.get();if(old>=value || peakConnections.compareAndSet(old,value)) return }
    }
    override fun create(call: Call): EventListener {
        val path=call.request().url.encodedPath
        if(path.contains("/Subtitles/",true) || listOf(".srt",".ass",".ssa",".vtt").any { path.endsWith(it,true) })
            return EventListener.NONE
        return object : EventListener() {
        private var dnsAt=0L; private var connectAt=0L;private var tlsAt=0L;private var requestAt=0L
        private var dns:Long?=null;private var connect:Long?=null;private var tls:Long?=null
        private var protocol="等待连接";private var remote="未提供"
        override fun callStart(call:Call) { requests.incrementAndGet();activeRequests.incrementAndGet() }
        override fun dnsStart(call:Call,domainName:String) { dnsAt=clock() }
        override fun dnsEnd(call:Call,domainName:String,inetAddressList:List<InetAddress>) { dns=(clock()-dnsAt).coerceAtLeast(0) }
        override fun connectStart(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy) { connectAt=clock();tls=null }
        override fun secureConnectStart(call:Call) { tlsAt=clock() }
        override fun secureConnectEnd(call:Call,handshake:Handshake?) { tls=(clock()-tlsAt).coerceAtLeast(0) }
        override fun connectEnd(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy,protocol:Protocol?) { connect=(clock()-connectAt).coerceAtLeast(0) }
        override fun connectionAcquired(call:Call,connection:Connection) {
            protocol=connection.protocol().toString();remote=connection.route().socketAddress.address?.hostAddress ?: "未提供"
            sockets?.observeConnection(connection.socket())
            liveConnections.add(connection);recordPeak(liveConnections.size)
        }
        override fun connectionReleased(call:Call,connection:Connection) { liveConnections.remove(connection) }
        override fun callEnd(call:Call) { activeRequests.decrementAndGet() }
        override fun requestHeadersEnd(call:Call,request:Request) { requestAt=clock() }
        override fun responseHeadersEnd(call:Call,response:Response) {
            if(response.code in 300..399) redirects.incrementAndGet()
            latest=Snapshot(protocol,remote,dns,connect,tls,(clock()-requestAt).coerceAtLeast(0),
                response.header("Content-Type") ?: "未提供",response.header("Content-Range") ?: "未提供",response.code)
        }
        override fun callFailed(call:Call,ioe:IOException) { failures.incrementAndGet();activeRequests.decrementAndGet() }
    }
    }
    fun summary():String {
        val s=latest
        fun duration(value:Long?)=value?.let { "${it}ms" } ?: "复用/未测"
        return "HTTP ${s.protocol} · IP ${s.remote}\nDNS ${duration(s.dnsMs)} · 连接含 TLS ${duration(s.connectMs)} · TLS ${duration(s.tlsMs)}\n"+
            "请求至响应头 ${duration(s.ttfbMs)}（不是 RTT）\n响应 ${s.status.takeIf { it>0 } ?: "等待"} · ${s.contentType}\nContent-Range ${s.range}\n"+
            "请求 ${requests.get()} · 重定向 ${redirects.get()} · 请求失败 ${failures.get()}\n活动请求 ${activeRequests.get()} · 同时连接峰值 ${peakConnections.get()}"
    }
}
