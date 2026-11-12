package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccPropertySelector @JvmOverloads constructor(
    val id: Long? = null,
    val name: String = "",
    val source: String = EvccApiContract.Source.AUTO,
    val areaId: Int? = null,
    val resolutionMode: String = EvccApiContract.ResolutionMode.AUTO_PRIORITY,
) : Parcelable {
    init { require(id != null || name.isNotBlank()) { "Property selector requires id or name" } }
    private constructor(parcel: Parcel) : this(
        parcel.readNullableLongCompat(), parcel.readRequiredString(), parcel.readRequiredString(),
        parcel.readNullableIntCompat(), parcel.readRequiredString(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeNullableLongCompat(id); writeString(name); writeString(source); writeNullableIntCompat(areaId)
        writeString(resolutionMode)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccPropertySelector> {
        override fun createFromParcel(parcel: Parcel) = EvccPropertySelector(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccPropertySelector>(size)
    }
}
