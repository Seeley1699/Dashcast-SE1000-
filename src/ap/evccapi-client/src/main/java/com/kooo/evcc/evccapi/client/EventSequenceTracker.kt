package com.kooo.evcc.evccapi.client

/** Pure monotonic-sequence helper kept separate so duplicate/gap behavior can be unit tested. */
internal class EventSequenceTracker {
    data class Observation(val accept: Boolean, val expectedSequence: Long? = null)

    private var lastSequence: Long? = null

    fun observe(sequence: Long): Observation {
        val previous = lastSequence
        if (previous != null && sequence <= previous) return Observation(accept = false)
        val expected = if (previous != null && previous != Long.MAX_VALUE && sequence > previous + 1L) {
            previous + 1L
        } else null
        lastSequence = sequence
        return Observation(accept = true, expectedSequence = expected)
    }

    fun reset() { lastSequence = null }

    fun reset(sequence: Long) { lastSequence = sequence }
}
