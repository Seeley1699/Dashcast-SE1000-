package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

/** API 1.2. Fixed wire order; use a new type for future extensions. */
data class EvccVolumeGroupState @JvmOverloads constructor(
    val status: String = EvccApiContract.Status.UNAVAILABLE,
    val message: String = "",
    val groupId: Int = -1,
    val current: Int = -1,
    val min: Int = -1,
    val max: Int = -1,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(),
        parcel.readRequiredString(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(status)
        writeString(message)
        writeInt(groupId)
        writeInt(current)
        writeInt(min)
        writeInt(max)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccVolumeGroupState> {
        override fun createFromParcel(parcel: Parcel) = EvccVolumeGroupState(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccVolumeGroupState>(size)
    }
}
