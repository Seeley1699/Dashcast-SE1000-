package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccCapability @JvmOverloads constructor(
    val id: String = "",
    val available: Boolean = false,
    val reason: String = "",
    val requiredPermission: String = "",
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), parcel.readBooleanCompat(), parcel.readRequiredString(), parcel.readRequiredString(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(id); writeBooleanCompat(available); writeString(reason); writeString(requiredPermission)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccCapability> {
        override fun createFromParcel(parcel: Parcel) = EvccCapability(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccCapability>(size)
    }
}
