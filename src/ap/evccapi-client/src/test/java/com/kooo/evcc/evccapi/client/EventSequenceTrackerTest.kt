package com.kooo.evcc.evccapi.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSequenceTrackerTest {
    @Test fun acceptsMonotonicEvents() {
        val tracker = EventSequenceTracker()
        assertTrue(tracker.observe(1).accept)
        assertNull(tracker.observe(2).expectedSequence)
    }

    @Test fun reportsGapAndRejectsDuplicateOrOutOfOrderEvents() {
        val tracker = EventSequenceTracker()
        tracker.observe(4)
        val gap = tracker.observe(7)
        assertTrue(gap.accept)
        assertEquals(5L, gap.expectedSequence)
        assertFalse(tracker.observe(7).accept)
        assertFalse(tracker.observe(6).accept)
    }

    @Test fun resetStartsANewGeneration() {
        val tracker = EventSequenceTracker()
        tracker.observe(20)
        tracker.reset()
        assertTrue(tracker.observe(1).accept)
        tracker.reset(9)
        assertEquals(10L, tracker.observe(12).expectedSequence)
    }

    @Test fun rejectsDuplicateZeroAndHandlesMaximumSequenceWithoutOverflow() {
        val tracker = EventSequenceTracker()
        assertTrue(tracker.observe(0).accept)
        assertFalse(tracker.observe(0).accept)

        tracker.reset(Long.MAX_VALUE)
        assertFalse(tracker.observe(Long.MAX_VALUE).accept)
    }
}
