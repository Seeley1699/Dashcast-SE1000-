package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccApiInfo @JvmOverloads constructor(
    val apiMajor: Int = EvccApiContract.API_MAJOR,
    val apiMinor: Int = EvccApiContract.API_MINOR,
    val contractHash: String = EvccApiContract.CONTRACT_HASH,
    val serviceVersionName: String = "",
    val serviceVersionCode: Long = 0,
    val capabilitiesRevision: Long = 0,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readInt(), parcel.readInt(), parcel.readRequiredString(), parcel.readRequiredString(),
        parcel.readLong(), parcel.readLong(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeInt(apiMajor); writeInt(apiMinor); writeString(contractHash); writeString(serviceVersionName)
        writeLong(serviceVersionCode); writeLong(capabilitiesRevision)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccApiInfo> {
        override fun createFromParcel(parcel: Parcel) = EvccApiInfo(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccApiInfo>(size)
    }
}
