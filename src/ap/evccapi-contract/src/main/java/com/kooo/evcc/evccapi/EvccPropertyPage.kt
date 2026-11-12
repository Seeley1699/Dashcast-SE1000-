package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccPropertyPage @JvmOverloads constructor(
    val requestId: String = "",
    val items: List<EvccPropertyRecord> = emptyList(),
    val nextPageToken: String = "",
    val snapshotToken: String = "",
    val status: String = EvccApiContract.Status.OK,
    val message: String = "",
    val sourceStates: List<EvccSourceState> = emptyList(),
) : Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(requestId); writeTypedList(items); writeString(nextPageToken); writeString(snapshotToken)
        writeString(status); writeString(message); writeTypedList(sourceStates)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccPropertyPage> {
        override fun createFromParcel(parcel: Parcel): EvccPropertyPage {
            val requestId = parcel.readRequiredString()
            val items = parcel.createTypedArrayList(EvccPropertyRecord.CREATOR).orEmpty()
            val nextPageToken = parcel.readRequiredString()
            val snapshotToken = parcel.readRequiredString()
            val status = parcel.readRequiredString()
            val message = parcel.readRequiredString()
            val states = parcel.createTypedArrayList(EvccSourceState.CREATOR).orEmpty()
            return EvccPropertyPage(requestId, items, nextPageToken, snapshotToken, status, message, states)
        }
        override fun newArray(size: Int) = arrayOfNulls<EvccPropertyPage>(size)
    }
}
