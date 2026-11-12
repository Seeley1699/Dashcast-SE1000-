package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VolumeParcelTest {
    @Test fun allNewTypesPreserveFieldsAndSequentialParcelBoundaries() {
        val group = EvccVolumeGroupState("AVAILABLE", "音量 🎵", 3, 12, 2, 30)
        val values: List<Pair<Parcelable, Parcelable.Creator<out Parcelable>>> = listOf(
            group to EvccVolumeGroupState.CREATOR,
            EvccVolumeGroupsResult("AVAILABLE", "ok", listOf(group)) to EvccVolumeGroupsResult.CREATOR,
            EvccVolumeSetRequest("s", 3, Int.MAX_VALUE, true) to EvccVolumeSetRequest.CREATOR,
            EvccVolumeAdjustRequest("a", 3, Int.MIN_VALUE, false) to EvccVolumeAdjustRequest.CREATOR,
            EvccVolumeWriteResult("w", "OK", "target only", 3, 20, 2, 20) to EvccVolumeWriteResult.CREATOR,
            EvccVolumeGroupsResult("UNSUPPORTED") to EvccVolumeGroupsResult.CREATOR,
        )
        val parcel = Parcel.obtain()
        try {
            values.forEach { it.first.writeToParcel(parcel, 0) }
            parcel.writeInt(0x12345678); parcel.setDataPosition(0)
            values.forEach { assertEquals(it.first, it.second.createFromParcel(parcel)) }
            assertEquals(0x12345678, parcel.readInt())
        } finally { parcel.recycle() }
    }
}
