package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccVariableWriteRequest @JvmOverloads constructor(
    val requestId: String = "",
    val name: String,
    val value: EvccValue,
    val validateOnly: Boolean = false,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), parcel.readRequiredString(), EvccValue.CREATOR.createFromParcel(parcel),
        parcel.readBooleanCompat(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(requestId); writeString(name); value.writeToParcel(this, flags); writeBooleanCompat(validateOnly)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccVariableWriteRequest> {
        override fun createFromParcel(parcel: Parcel) = EvccVariableWriteRequest(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccVariableWriteRequest>(size)
    }
}
