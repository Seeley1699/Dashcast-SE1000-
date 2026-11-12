package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccShellRequest @JvmOverloads constructor(
    val requestId: String,
    val command: String,
    val timeoutMs: Long = 10_000,
    val includeRawOutput: Boolean = false,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), parcel.readRequiredString(), parcel.readLong(), parcel.readBooleanCompat(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(requestId); writeString(command); writeLong(timeoutMs); writeBooleanCompat(includeRawOutput)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccShellRequest> {
        override fun createFromParcel(parcel: Parcel) = EvccShellRequest(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccShellRequest>(size)
    }
}
