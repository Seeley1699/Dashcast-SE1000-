package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccShellResult @JvmOverloads constructor(
    val requestId: String = "",
    val status: String = EvccApiContract.Status.PENDING,
    val exitCode: Int = -1,
    val stdout: String = "",
    val stderr: String = "",
    val truncated: Boolean = false,
    val durationMs: Long = 0,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), parcel.readRequiredString(), parcel.readInt(), parcel.readRequiredString(),
        parcel.readRequiredString(), parcel.readBooleanCompat(), parcel.readLong(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(requestId); writeString(status); writeInt(exitCode); writeString(stdout); writeString(stderr)
        writeBooleanCompat(truncated); writeLong(durationMs)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccShellResult> {
        override fun createFromParcel(parcel: Parcel) = EvccShellResult(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccShellResult>(size)
    }
}
