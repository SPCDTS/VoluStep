package dev.spcdts.volumemapper.runtime

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

internal const val COORDINATOR_TICK_INTERVAL_MILLIS = 20L

// The fastest configured hold advances every 60 ms. A 20 ms clock aligns with that grid and limits
// scheduler jitter without increasing AudioManager writes: the reducer requests only changed slots.
internal const val COORDINATOR_MIN_WRITE_INTERVAL_MILLIS = COORDINATOR_TICK_INTERVAL_MILLIS

/**
 * Actor-owned FIFO that separates write-attempt throttling from post-write verification time.
 * A Binder call may finish well after its attempt began; using completion time for both concerns
 * shifts the gate into the next ticker slot and can make a newer target replace an older one.
 */
internal class CoordinatorWriteQueue<T>(
    private val minimumIntervalMillis: Long = COORDINATOR_MIN_WRITE_INTERVAL_MILLIS,
) {
    private val pending = ArrayDeque<T>()
    private var lastAttemptStartedAtMillis = Long.MIN_VALUE

    init {
        require(minimumIntervalMillis >= 0L) { "minimumIntervalMillis must not be negative" }
    }

    val hasPending: Boolean
        get() = pending.isNotEmpty()

    fun canStartAttempt(nowMillis: Long): Boolean =
        lastAttemptStartedAtMillis == Long.MIN_VALUE ||
            nowMillis - lastAttemptStartedAtMillis >= minimumIntervalMillis

    fun recordAttemptStarted(nowMillis: Long) {
        lastAttemptStartedAtMillis = nowMillis
    }

    fun enqueue(value: T) {
        pending.addLast(value)
    }

    fun takeNextIfReady(nowMillis: Long): T? {
        if (pending.isEmpty() || !canStartAttempt(nowMillis)) return null
        return pending.removeFirst()
    }

    fun clearPending() {
        pending.clear()
    }

    fun reset() {
        pending.clear()
        lastAttemptStartedAtMillis = Long.MIN_VALUE
    }
}

internal sealed interface CoordinatorMailboxMessage<out C, out T> {
    data class Control<C>(val value: C) : CoordinatorMailboxMessage<C, Nothing>
    data class LatestTick<T>(val value: T) : CoordinatorMailboxMessage<Nothing, T>
}

/** Gives control/release commands priority while retaining only the latest absolute-time tick. */
internal suspend fun <C : Any, T : Any> receiveNextCoordinatorMessage(
    controls: Channel<C>,
    ticks: Channel<T>,
): CoordinatorMailboxMessage<C, T> {
    controls.tryReceive().getOrNull()?.let { ready ->
        return CoordinatorMailboxMessage.Control(ready)
    }
    return select {
        controls.onReceive { CoordinatorMailboxMessage.Control(it) }
        ticks.onReceive { CoordinatorMailboxMessage.LatestTick(it) }
    }
}
