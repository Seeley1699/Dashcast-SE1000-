package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

/** API 1.1. Fixed wire order; extend with a new type, never append fields. */
data class EvccMusicCommand @JvmOverloads constructor(
    val requestId: String = "",
    val command: String = "",
    val positionMs: Long = 0L,
    val favorite: Boolean = false,
    val expectedTrackId: String = "",
    val validateOnly: Boolean = false,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readLong(),
        parcel.readInt() != 0,
        parcel.readString().orEmpty(),
        parcel.readInt() != 0,
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(requestId)
        writeString(command)
        writeLong(positionMs)
        writeInt(if (favorite) 1 else 0)
        writeString(expectedTrackId)
        writeInt(if (validateOnly) 1 else 0)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccMusicCommand> {
        const val PLAY = "PLAY"
        const val PAUSE = "PAUSE"
        const val TOGGLE = "TOGGLE"
        const val NEXT = "NEXT"
        const val PREVIOUS = "PREVIOUS"
        const val SEEK = "SEEK"
        const val SET_FAVORITE = "SET_FAVORITE"
        override fun createFromParcel(parcel: Parcel) = EvccMusicCommand(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccMusicCommand>(size)
    }
}
