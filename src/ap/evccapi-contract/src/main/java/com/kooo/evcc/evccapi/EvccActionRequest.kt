package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccActionRequest @JvmOverloads constructor(
    val requestId: String = "",
    val actionId: String,
    val argumentsJson: String = "{}",
    val validateOnly: Boolean = false,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), parcel.readRequiredString(), parcel.readRequiredString(), parcel.readBooleanCompat(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(requestId); writeString(actionId); writeString(argumentsJson); writeBooleanCompat(validateOnly)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccActionRequest> {
        override fun createFromParcel(parcel: Parcel) = EvccActionRequest(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccActionRequest>(size)
    }
}
