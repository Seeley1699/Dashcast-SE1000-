package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccPropertyDescriptor @JvmOverloads constructor(
    val source: String = "",
    val canonicalId: Long = 0,
    val nativeId: String = "",
    val aliases: List<String> = emptyList(),
    val hexId: String = "",
    val name: String = "",
    val description: String? = null,
    val group: String = "",
    val areaType: String = "",
    val areaId: Int = 0,
    val areaName: String = "",
    val dataType: String = "",
    val access: String = "",
    val changeMode: String = "",
    val enumOptions: List<String> = emptyList(),
    val userNote: String? = null,
    val sourceGeneration: Long = 0,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), parcel.readLong(), parcel.readRequiredString(),
        parcel.createStringArrayList().orEmpty(), parcel.readRequiredString(), parcel.readRequiredString(), parcel.readString(),
        parcel.readRequiredString(), parcel.readRequiredString(), parcel.readInt(), parcel.readRequiredString(),
        parcel.readRequiredString(), parcel.readRequiredString(), parcel.readRequiredString(),
        parcel.createStringArrayList().orEmpty(), parcel.readString(), parcel.readLong(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(source); writeLong(canonicalId); writeString(nativeId); writeStringList(aliases); writeString(hexId)
        writeString(name); writeString(description); writeString(group); writeString(areaType); writeInt(areaId)
        writeString(areaName); writeString(dataType); writeString(access); writeString(changeMode)
        writeStringList(enumOptions); writeString(userNote); writeLong(sourceGeneration)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccPropertyDescriptor> {
        override fun createFromParcel(parcel: Parcel) = EvccPropertyDescriptor(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccPropertyDescriptor>(size)
    }
}
