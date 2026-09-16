package dev.spcdts.volumemapper.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyDeliveryMonitorTest {
    @Test
    fun `delivery diagnostics distinguish timely and expired presses and reset on reconnect`() {
        val monitor = KeyDeliveryMonitor()
        monitor.setAnchorAttached(true)
        monitor.recordDown(1_000, 501)
        monitor.recordDown(1_000, 500)
        assertEquals(KeyDeliveryState(2, 1, 500, true), monitor.state.value)
        monitor.reset()
        assertEquals(KeyDeliveryState(), monitor.state.value)
    }

    @Test
    fun `future timestamps and subtraction overflow cannot produce negative latency`() {
        val monitor = KeyDeliveryMonitor()
        monitor.recordDown(10, 11)
        assertEquals(0L, monitor.state.value.lastDelayMillis)
        monitor.recordDown(Long.MAX_VALUE, -1)
        assertEquals(Long.MAX_VALUE, monitor.state.value.lastDelayMillis)
        assertEquals(1L, monitor.state.value.expiredDowns)
    }
}
