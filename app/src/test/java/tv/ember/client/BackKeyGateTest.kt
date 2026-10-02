package tv.ember.client

import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.ui.BackKeyGate

class BackKeyGateTest {
    @Test fun theSamePhysicalPressCannotBackThroughAnotherActivity() {
        val gate = BackKeyGate()
        assertTrue(gate.accept(1000, false))
        assertFalse(gate.accept(1000, false))
        assertFalse(gate.accept(1000, false))
        assertTrue(gate.accept(1200, false))
    }
    @Test fun aCanceledPressDoesNotNavigate() {
        val gate = BackKeyGate()
        assertFalse(gate.accept(1000, true))
        assertTrue(gate.accept(1100, false))
    }
}
