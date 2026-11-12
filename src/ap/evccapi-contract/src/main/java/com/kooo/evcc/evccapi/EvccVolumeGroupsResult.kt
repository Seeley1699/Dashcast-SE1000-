package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

/** API 1.2. Fixed wire order; use a new type for future extensions. */
data class EvccVolumeGroupsResult @JvmOverloads constructor(
    val status: String = EvccApiContract.Status.UNAVAILABLE,
    val message: String = "",
    val groups: List<EvccVolumeGroupState> = emptyList(),
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(),
        parcel.readRequiredString(),
        parcel.createTypedArrayList(EvccVolumeGroupState.CREATOR).orEmpty(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(status)
        writeString(message)
        writeTypedList(groups)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccVolumeGroupsResult> {
        override fun createFromParcel(parcel: Parcel) = EvccVolumeGroupsResult(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccVolumeGroupsResult>(size)
    }
}
