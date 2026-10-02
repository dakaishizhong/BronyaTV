package tv.ember.client

import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.monitor.TransferWindow
import tv.ember.client.monitor.PlayerStatsMonitor

class TransferDiagnosticsTest {
    @Test fun ratesIncludeIdleTimeAndRetainTotals() {
        var now=0L;val window=TransferWindow { now }
        window.add(1048576);now=1000
        var sample=window.sample();assertEquals(1048576,sample.rate);assertEquals(1048576,sample.total)
        repeat(4) { now+=1000;sample=window.sample() }
        assertEquals(0,sample.rate);assertEquals(1048576/5,sample.average);assertEquals(1048576,sample.peak);assertEquals(5000L,sample.idleMs)
        now=32000;sample=window.sample();assertEquals(0,sample.peak);assertEquals(1048576,sample.total)
    }
    @Test fun diagnosticsHideSignedQueriesCredentialsAndFragments() {
        val masked=PlayerStatsMonitor.redact("https://alice:password@host/original.mkv?api_key=secret&sig=private&X-Amz-Credential=credentials&foo=opaque#token")
        assertEquals("https://***@host/original.mkv?api_key=***&sig=***&X-Amz-Credential=***&foo=***",masked)
    }
}
