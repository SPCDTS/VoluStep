package dev.spcdts.volumemapper.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityKeyFreshnessTest {
    @Test
    fun `event age boundary handles deadline clock skew and overflow safely`() {
        assertFalse(isAccessibilityKeyEventExpired(10_499L, 10_000L))
        assertTrue(isAccessibilityKeyEventExpired(10_500L, 10_000L))
        assertFalse(isAccessibilityKeyEventExpired(9_999L, 10_000L))
        assertTrue(isAccessibilityKeyEventExpired(Long.MAX_VALUE, Long.MIN_VALUE))
    }

    @Test
    fun `expired key disposition drains only an already owned gesture`() {
        val cases = listOf(
            Triple(true to false, ExpiredKeyEventDisposition.PASS_THROUGH, "expired initial down"),
            Triple(false to false, ExpiredKeyEventDisposition.PASS_THROUGH, "expired unowned up"),
            Triple(false to true, ExpiredKeyEventDisposition.DRAIN_OWNED_GESTURE, "expired owned up"),
        )

        cases.forEach { (input, expected, label) ->
            assertEquals(
                label,
                expected,
                expiredKeyEventDisposition(
                    isExpired = true,
                    isInitialDown = input.first,
                    ownsGesture = input.second,
                ),
            )
        }
        assertEquals(
            ExpiredKeyEventDisposition.PROCESS,
            expiredKeyEventDisposition(
                isExpired = false,
                isInitialDown = true,
                ownsGesture = false,
            ),
        )
    }
}
