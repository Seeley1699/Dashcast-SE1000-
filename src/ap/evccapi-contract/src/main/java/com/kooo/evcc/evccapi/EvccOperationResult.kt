package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccOperationResult @JvmOverloads constructor(
    val requestId: String = "",
    val status: String = EvccApiContract.Status.OK,
    val message: String = "",
    val acceptedCount: Int = 0,
    val failedCount: Int = 0,
) : Parcelable {
    val isSuccess: Boolean get() = status == EvccApiContract.Status.OK || status == EvccApiContract.Status.ACCEPTED
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), parcel.readRequiredString(), parcel.readRequiredString(), parcel.readInt(), parcel.readInt(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(requestId); writeString(status); writeString(message); writeInt(acceptedCount); writeInt(failedCount)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccOperationResult> {
        override fun createFromParcel(parcel: Parcel) = EvccOperationResult(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccOperationResult>(size)
    }
}
