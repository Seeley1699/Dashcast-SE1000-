package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccPropertyRecord @JvmOverloads constructor(
    val requestId: String = "",
    val requestedSelector: EvccPropertySelector? = null,
    val resolvedSource: String = "",
    val descriptor: EvccPropertyDescriptor? = null,
    val value: EvccValue? = null,
    val status: String = EvccApiContract.Status.PENDING,
    val message: String = "",
    val candidates: List<EvccPropertyDescriptor> = emptyList(),
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), parcel.readOptional(EvccPropertySelector.CREATOR), parcel.readRequiredString(),
        parcel.readOptional(EvccPropertyDescriptor.CREATOR), parcel.readOptional(EvccValue.CREATOR),
        parcel.readRequiredString(), parcel.readRequiredString(),
        parcel.createTypedArrayList(EvccPropertyDescriptor.CREATOR).orEmpty(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(requestId); writeOptional(requestedSelector, flags); writeString(resolvedSource)
        writeOptional(descriptor, flags); writeOptional(value, flags); writeString(status); writeString(message)
        writeTypedList(candidates)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccPropertyRecord> {
        override fun createFromParcel(parcel: Parcel) = EvccPropertyRecord(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccPropertyRecord>(size)
    }
}
