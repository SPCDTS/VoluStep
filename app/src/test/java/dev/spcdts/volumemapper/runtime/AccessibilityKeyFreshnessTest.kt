package dev.spcdts.volumemapper.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityKeyFreshnessTest {
    @Test
    fun `event becomes expired at the AOSP dispatch deadline`() {
        assertFalse(
            isAccessibilityKeyEventExpired(
                nowUptimeMillis = 10_499L,
                eventTimeMillis = 10_000L,
            ),
        )
        assertTrue(
            isAccessibilityKeyEventExpired(
                nowUptimeMillis = 10_500L,
                eventTimeMillis = 10_000L,
            ),
        )
    }

    @Test
    fun `negative age is treated as fresh`() {
        assertFalse(
            isAccessibilityKeyEventExpired(
                nowUptimeMillis = 9_999L,
                eventTimeMillis = 10_000L,
            ),
        )
    }

    @Test
    fun `positive age subtraction overflow is treated as expired`() {
        assertTrue(
            isAccessibilityKeyEventExpired(
                nowUptimeMillis = Long.MAX_VALUE,
                eventTimeMillis = Long.MIN_VALUE,
            ),
        )
    }

    @Test
    fun `expired initial down always passes through without ownership`() {
        assertEquals(
            ExpiredKeyEventDisposition.PASS_THROUGH,
            expiredKeyEventDisposition(
                isExpired = true,
                isInitialDown = true,
                ownsGesture = false,
            ),
        )
    }

    @Test
    fun `expired initial down never reuses an existing owner`() {
        assertEquals(
            ExpiredKeyEventDisposition.PASS_THROUGH,
            expiredKeyEventDisposition(
                isExpired = true,
                isInitialDown = true,
                ownsGesture = true,
            ),
        )
    }

    @Test
    fun `expired up drains its matching owner instead of reaching reducer`() {
        assertEquals(
            ExpiredKeyEventDisposition.DRAIN_OWNED_GESTURE,
            expiredKeyEventDisposition(
                isExpired = true,
                isInitialDown = false,
                ownsGesture = true,
            ),
        )
    }

    @Test
    fun `expired unowned up passes through`() {
        assertEquals(
            ExpiredKeyEventDisposition.PASS_THROUGH,
            expiredKeyEventDisposition(
                isExpired = true,
                isInitialDown = false,
                ownsGesture = false,
            ),
        )
    }

    @Test
    fun `fresh down and up keep the existing handling path`() {
        assertEquals(
            ExpiredKeyEventDisposition.PROCESS,
            expiredKeyEventDisposition(
                isExpired = false,
                isInitialDown = true,
                ownsGesture = false,
            ),
        )
        assertEquals(
            ExpiredKeyEventDisposition.PROCESS,
            expiredKeyEventDisposition(
                isExpired = false,
                isInitialDown = false,
                ownsGesture = true,
            ),
        )
    }
}
