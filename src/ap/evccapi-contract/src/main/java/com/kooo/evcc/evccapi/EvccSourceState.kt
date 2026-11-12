package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccSourceState @JvmOverloads constructor(
    val source: String = "",
    val state: String = EvccApiContract.Status.UNAVAILABLE,
    val message: String = "",
    val sourceGeneration: Long = 0,
    val updatedAt: Long = 0,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), parcel.readRequiredString(), parcel.readRequiredString(), parcel.readLong(), parcel.readLong(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(source); writeString(state); writeString(message); writeLong(sourceGeneration); writeLong(updatedAt)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccSourceState> {
        override fun createFromParcel(parcel: Parcel) = EvccSourceState(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccSourceState>(size)
    }
}
