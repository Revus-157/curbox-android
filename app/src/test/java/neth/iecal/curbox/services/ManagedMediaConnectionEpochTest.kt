package neth.iecal.curbox.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedMediaConnectionEpochTest {
    @Test
    fun disconnectedEpochCannotApplyAfterReconnect() {
        val epochs = ManagedMediaConnectionEpoch()
        val first = epochs.connected()

        epochs.disconnected()
        val second = epochs.connected()

        assertFalse(epochs.accepts(first))
        assertTrue(epochs.accepts(second))
    }

    @Test
    fun disconnectedEpochAcceptsNoQueuedWork() {
        val epochs = ManagedMediaConnectionEpoch()
        val connected = epochs.connected()

        epochs.disconnected()

        assertFalse(epochs.accepts(connected))
    }
}
