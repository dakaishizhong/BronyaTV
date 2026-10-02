package tv.ember.client.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** SO_RCVBUF is a per-socket, unprivileged hint. Never change system-wide TCP settings. */
class ReceiveBufferSocketFactory(private val requestedBytes: Int) : SocketFactory() {
    init { require(requestedBytes >= 0) }
    @Volatile private var reportedBytes = 0
    @Volatile private var connectedSocket: Socket? = null
    val effectiveBytes: Int get() = runCatching { connectedSocket?.takeUnless { it.isClosed }?.receiveBufferSize }.getOrNull() ?: reportedBytes
    @Volatile var limited = false; private set

    override fun createSocket(): Socket = Socket().also { socket ->
        if (requestedBytes > 0) runCatching { socket.receiveBufferSize = requestedBytes }.onFailure { limited = true }
        reportedBytes = runCatching { socket.receiveBufferSize }.getOrDefault(0)
    }
    fun observeConnection(socket: Socket) { connectedSocket = socket }
    override fun createSocket(host: String, port: Int): Socket = connect(InetSocketAddress(host, port))
    override fun createSocket(host: InetAddress, port: Int): Socket = connect(InetSocketAddress(host, port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = connect(InetSocketAddress(host, port), InetSocketAddress(local, localPort))
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = connect(InetSocketAddress(host, port), InetSocketAddress(local, localPort))
    private fun connect(remote: InetSocketAddress, local: InetSocketAddress? = null): Socket {
        val socket = createSocket()
        try {
            if (local != null) socket.bind(local)
            socket.connect(remote)
            return socket
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }
}
