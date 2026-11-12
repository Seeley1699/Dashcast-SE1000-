package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

/** API 1.2. Fixed wire order; use a new type for future extensions. */
data class EvccVolumeAdjustRequest @JvmOverloads constructor(
    val requestId: String = "",
    val groupId: Int = -1,
    val delta: Int = 0,
    val validateOnly: Boolean = false,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt() != 0,
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(requestId)
        writeInt(groupId)
        writeInt(delta)
        writeInt(if (validateOnly) 1 else 0)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccVolumeAdjustRequest> {
        override fun createFromParcel(parcel: Parcel) = EvccVolumeAdjustRequest(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccVolumeAdjustRequest>(size)
    }
}
