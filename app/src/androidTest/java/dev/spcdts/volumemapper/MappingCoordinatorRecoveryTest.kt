package dev.spcdts.volumemapper

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.spcdts.volumemapper.audio.VolumeBackend
import dev.spcdts.volumemapper.core.AudioRouteDescriptor
import dev.spcdts.volumemapper.core.AudioRouteType
import dev.spcdts.volumemapper.core.RouteConfidence
import dev.spcdts.volumemapper.core.RouteVolumeRange
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import dev.spcdts.volumemapper.core.StepVolumeMap
import dev.spcdts.volumemapper.data.VolumeMapperSettings
import dev.spcdts.volumemapper.runtime.MappingCoordinator
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用可控音频边界验证 actor；不写设备全局音量，不依赖手机的路由硬件。 */
@RunWith(AndroidJUnit4::class)
class MappingCoordinatorRecoveryTest {
    @Test
    fun backendExceptionReturnsKeysToSystemAndRetryRestoresActor() = withController { backend, coordinator ->
        backend.throwOnRefresh = true
        val down = keyDown()
        assertTrue(coordinator.handleAccessibilityKey(down))
        await { coordinator.runtime.value.isFailOpen }
        assertTrue(coordinator.handleAccessibilityKey(keyUp(down)))
        assertFalse(coordinator.handleAccessibilityKey(keyDown()))
        assertTrue(backend.writes.isEmpty())

        backend.throwOnRefresh = false
        coordinator.retry()
        await { coordinator.runtime.value.canInterceptKeys && eligible(coordinator) }
        val nextDown = keyDown()
        assertTrue(coordinator.handleAccessibilityKey(nextDown))
        assertTrue(coordinator.handleAccessibilityKey(keyUp(nextDown)))
        await { backend.writes.isNotEmpty() }
        assertFalse(coordinator.runtime.value.isFailOpen)
    }

    @Test
    fun routeReconnectCancelsHeldGestureBeforeWritingToNewRoute() = withController { backend, coordinator ->
        val down = keyDown()
        assertTrue(coordinator.handleAccessibilityKey(down))
        await { backend.writes.isNotEmpty() }
        backend.observed = backend.observed.copy(
            route = backend.observed.route.copy(stableId = "reconnected"),
            range = RouteVolumeRange(0, 30), currentIndex = 20,
        )
        backend.changes.tryEmit(Unit)
        await { coordinator.runtime.value.snapshot?.route?.stableId == "reconnected" }
        val countAfterTransition = backend.writes.size
        val repeat = KeyEvent.changeTimeRepeat(down, SystemClock.uptimeMillis(), 1)
        assertTrue(coordinator.handleAccessibilityKey(repeat))
        SystemClock.sleep(550)
        assertEquals(countAfterTransition, backend.writes.size)
        assertTrue(coordinator.handleAccessibilityKey(keyUp(down)))
        await { eligible(coordinator) }
        val fresh = keyDown()
        assertTrue(coordinator.handleAccessibilityKey(fresh))
        assertTrue(coordinator.handleAccessibilityKey(keyUp(fresh)))
        await { backend.writes.size > countAfterTransition }
        assertTrue(backend.writes.last() in 0..30)
    }

    @Test
    fun holdWithHeartbeatsContinuesPastWatchdogAndReleaseStopsWrites() = withController { backend, coordinator ->
        val down = keyDown()
        assertTrue(coordinator.handleAccessibilityKey(down))
        val deadline = SystemClock.uptimeMillis() + 2_600
        var repeat = 1
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            assertTrue(coordinator.handleAccessibilityKey(
                KeyEvent.changeTimeRepeat(down, SystemClock.uptimeMillis(), repeat++),
            ))
        }
        // 500 ms 连按间隔：2 秒之后仍必须前进，不能被丢 UP 看门狗误取消。
        assertTrue("长按应跨过 2 秒看门狗继续写入", backend.writes.size >= 6)
        assertTrue(coordinator.handleAccessibilityKey(keyUp(down)))
        SystemClock.sleep(500)
        val countAtRelease = backend.writes.size
        SystemClock.sleep(600)
        assertEquals(countAtRelease, backend.writes.size)
    }

    @Test
    fun lostReleaseStopsWritesAndNextPressCanClaimOwnership() = withController { backend, coordinator ->
        assertTrue(coordinator.handleAccessibilityKey(keyDown()))
        await { backend.writes.isNotEmpty() }
        SystemClock.sleep(2_400)
        val countAfterWatchdog = backend.writes.size
        SystemClock.sleep(550)
        assertEquals(countAfterWatchdog, backend.writes.size)
        val next = keyDown()
        assertTrue(coordinator.handleAccessibilityKey(next))
        assertTrue(coordinator.handleAccessibilityKey(keyUp(next)))
        await { backend.writes.size > countAfterWatchdog }
    }

    private fun withController(test: (FakeBackend, MappingCoordinator) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val backend = FakeBackend()
        val settings = VolumeMapperSettings().let {
            it.copy(
                outputMap = StepVolumeMap(150, 25, listOf(0, 13, 25), listOf(0, 20, 150)),
                keyConfig = it.keyConfig.copy(holdStepIntervalMillis = 500),
            )
        }
        val coordinator = MappingCoordinator(backend, MutableStateFlow(settings), scope)
        try {
            coordinator.setAccessibilityConnected(true)
            coordinator.onForegroundServiceStarted()
            coordinator.arm()
            await { coordinator.runtime.value.canInterceptKeys && eligible(coordinator) }
            test(backend, coordinator)
        } finally {
            coordinator.disarm()
            scope.cancel()
        }
    }

    private class FakeBackend : VolumeBackend {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val writes = CopyOnWriteArrayList<Int>()
        @Volatile var throwOnRefresh = false
        @Volatile var observed = RouteVolumeSnapshot(
            AudioRouteDescriptor("speaker", AudioRouteType.BUILT_IN_SPEAKER, confidence = RouteConfidence.CONFIRMED),
            RouteVolumeRange(0, 150), 150, 0,
        )
        override val isVolumeFixed = false
        override val isMediaContextSafe = true
        override fun refreshMediaContextSafety(): Boolean {
            check(!throwOnRefresh) { "Injected audio service failure" }
            return true
        }
        override fun snapshot() = Result.success(observed)
        override fun setMediaVolume(index: Int, showSystemUi: Boolean): Result<Unit> {
            observed = observed.copy(currentIndex = index)
            writes.add(index)
            return Result.success(Unit)
        }
        override fun environmentChanges() = changes
    }

    private fun keyDown(): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0)
    }
    private fun keyUp(down: KeyEvent) = KeyEvent.changeTimeRepeat(
        KeyEvent.changeAction(down, KeyEvent.ACTION_UP), SystemClock.uptimeMillis(), 0,
    )
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Coordinator condition timed out" }
            SystemClock.sleep(20)
        }
    }
    private fun eligible(coordinator: MappingCoordinator): Boolean {
        val field = MappingCoordinator::class.java.getDeclaredField("eligible").apply { isAccessible = true }
        return (field.get(coordinator) as java.util.concurrent.atomic.AtomicBoolean).get()
    }
}
