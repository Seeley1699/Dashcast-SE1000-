package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccSessionInfo @JvmOverloads constructor(
    val sessionId: String = "",
    val clientPackage: String = "",
    val grantedPermissions: List<String> = emptyList(),
    val preset: String = EvccApiContract.Preset.CUSTOM,
    val grantRevision: Long = 0,
    val createdAt: Long = 0,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), parcel.readRequiredString(),
        parcel.createStringArrayList().orEmpty(), parcel.readRequiredString(), parcel.readLong(), parcel.readLong(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(sessionId); writeString(clientPackage); writeStringList(grantedPermissions); writeString(preset)
        writeLong(grantRevision); writeLong(createdAt)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccSessionInfo> {
        override fun createFromParcel(parcel: Parcel) = EvccSessionInfo(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccSessionInfo>(size)
    }
}
