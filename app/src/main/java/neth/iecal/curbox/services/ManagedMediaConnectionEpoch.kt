package neth.iecal.curbox.services

/** Invalidates work posted by a notification-listener connection after disconnect/reconnect. */
internal class ManagedMediaConnectionEpoch {
    private var generation = 0L
    private var connectedState = false

    fun connected(): Long {
        generation += 1
        connectedState = true
        return generation
    }

    fun disconnected() {
        generation += 1
        connectedState = false
    }

    fun accepts(candidate: Long): Boolean = connectedState && generation == candidate
}
