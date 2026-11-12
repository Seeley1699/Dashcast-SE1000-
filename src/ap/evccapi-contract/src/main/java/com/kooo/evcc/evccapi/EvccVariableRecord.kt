package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccVariableRecord @JvmOverloads constructor(
    val name: String = "",
    val value: EvccValue? = null,
    val status: String = EvccApiContract.Status.PENDING,
    val message: String = "",
    val updatedAt: Long = 0,
    val mutable: Boolean = false,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), parcel.readOptional(EvccValue.CREATOR), parcel.readRequiredString(),
        parcel.readRequiredString(), parcel.readLong(), parcel.readBooleanCompat(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(name); writeOptional(value, flags); writeString(status); writeString(message); writeLong(updatedAt)
        writeBooleanCompat(mutable)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccVariableRecord> {
        override fun createFromParcel(parcel: Parcel) = EvccVariableRecord(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccVariableRecord>(size)
    }
}
