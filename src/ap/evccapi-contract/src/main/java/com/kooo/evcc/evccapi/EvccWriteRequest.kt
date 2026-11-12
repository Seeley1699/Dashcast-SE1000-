package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccWriteRequest @JvmOverloads constructor(
    val requestId: String = "",
    val selector: EvccPropertySelector,
    val value: EvccValue,
    val validateOnly: Boolean = false,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), EvccPropertySelector.CREATOR.createFromParcel(parcel),
        EvccValue.CREATOR.createFromParcel(parcel), parcel.readBooleanCompat(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(requestId); selector.writeToParcel(this, flags); value.writeToParcel(this, flags)
        writeBooleanCompat(validateOnly)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccWriteRequest> {
        override fun createFromParcel(parcel: Parcel) = EvccWriteRequest(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccWriteRequest>(size)
    }
}
