package tv.ember.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.ember.client.network.ReceiveBufferSocketFactory

@RunWith(AndroidJUnit4::class)
class ReceiveBufferDeviceTest {
    @Test fun normalAppCanConfigureReceiveBuffersBeforeConnectingWithoutRoot() {
        assertTrue(android.os.Process.myUid() >= 10000)
        for (kb in listOf(256,512,1024,2048,4096)) {
            val factory=ReceiveBufferSocketFactory(kb*1024)
            factory.createSocket().use { socket ->
                assertFalse(socket.isConnected)
                assertFalse(factory.limited)
                assertTrue(socket.receiveBufferSize>0)
                assertEquals(socket.receiveBufferSize,factory.effectiveBytes)
                println("SO_RCVBUF requested=${kb}KB reported=${socket.receiveBufferSize/1024}KB uid=${android.os.Process.myUid()}")
            }
        }
    }
    @Test fun automaticModeKeepsThePlatformSocketDefault() {
        val factory=ReceiveBufferSocketFactory(0)
        factory.createSocket().use { socket ->
            assertTrue(socket.receiveBufferSize>0)
            assertFalse(factory.limited)
        }
    }
}
