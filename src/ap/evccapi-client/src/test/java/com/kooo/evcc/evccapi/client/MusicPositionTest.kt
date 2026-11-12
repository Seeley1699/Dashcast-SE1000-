package com.kooo.evcc.evccapi.client

import com.kooo.evcc.evccapi.EvccApiContract.Status
import com.kooo.evcc.evccapi.EvccMusicState
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicPositionTest {
    private val playing = EvccMusicState(status = Status.AVAILABLE, playbackState = "PLAYING",
        positionMs = 1000, positionAtElapsedMs = 2000, playbackSpeed = 2f, durationMs = 10000)
    @Test fun extrapolatesSpeedAndClampsToDuration() {
        assertEquals(3000, EvccMusicResources.positionAt(playing, 3000))
        assertEquals(10000, EvccMusicResources.positionAt(playing, 100000))
    }
    @Test fun pauseUnavailableAndMissingAnchorNeverAdvance() {
        assertEquals(1000, EvccMusicResources.positionAt(playing.copy(playbackState = "PAUSED_OR_STOPPED"), 3000))
        assertEquals(1000, EvccMusicResources.positionAt(playing.copy(status = Status.UNAVAILABLE), 3000))
        assertEquals(1000, EvccMusicResources.positionAt(playing.copy(positionAtElapsedMs = 0), 3000))
    }
    @Test fun backwardsClockAndInvalidSpeedDoNotInventProgress() {
        assertEquals(1000, EvccMusicResources.positionAt(playing, 1000))
        assertEquals(1000, EvccMusicResources.positionAt(playing.copy(playbackSpeed = Float.NaN), 3000))
        assertEquals(1000, EvccMusicResources.positionAt(playing.copy(playbackSpeed = -1f), 3000))
    }
    @Test fun unknownPositionStaysUnknown() {
        assertEquals(-1L, EvccMusicResources.positionAt(playing.copy(positionMs = -1), 3000))
    }
    @Test fun unknownDurationAllowsProgressAndLargeClockSaturates() {
        assertEquals(3000, EvccMusicResources.positionAt(playing.copy(durationMs = -1), 3000))
        assertEquals(Long.MAX_VALUE, EvccMusicResources.positionAt(playing.copy(durationMs = -1), Long.MAX_VALUE))
    }
}
