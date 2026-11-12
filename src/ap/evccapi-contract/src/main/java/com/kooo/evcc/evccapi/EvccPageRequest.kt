package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccPageRequest @JvmOverloads constructor(
    val requestId: String = "",
    val pageToken: String = "",
    val source: String = EvccApiContract.Source.AUTO,
    val query: String = "",
    val limit: Int = EvccApiContract.DEFAULT_PAGE_SIZE,
    val includeValues: Boolean = false,
    val mode: String = EvccApiContract.DirectoryMode.DIRECTORY,
    /** Token returned by the first page. Required for stable continuation pages. */
    val snapshotToken: String = "",
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), parcel.readRequiredString(), parcel.readRequiredString(), parcel.readRequiredString(),
        parcel.readInt(), parcel.readBooleanCompat(), parcel.readRequiredString(), parcel.readRequiredString(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(requestId); writeString(pageToken); writeString(source); writeString(query); writeInt(limit)
        writeBooleanCompat(includeValues); writeString(mode); writeString(snapshotToken)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccPageRequest> {
        override fun createFromParcel(parcel: Parcel) = EvccPageRequest(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccPageRequest>(size)
    }
}
