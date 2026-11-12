package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable
import android.os.ParcelFileDescriptor
import java.io.Closeable

/** API 1.1 immutable resource. The receiving caller owns and must close descriptor. */
data class EvccMusicResource @JvmOverloads constructor(
    val status: String = EvccApiContract.Status.NOT_FOUND,
    val message: String = "",
    val trackId: String = "",
    val revision: Long = 0L,
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val descriptor: ParcelFileDescriptor? = null,
) : Parcelable, Closeable {
    private constructor(p: Parcel) : this(
        p.readString().orEmpty(), p.readString().orEmpty(), p.readString().orEmpty(), p.readLong(),
        p.readString().orEmpty(), p.readLong(),
        if (p.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(p) else null,
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(status); writeString(message); writeString(trackId); writeLong(revision)
        writeString(mimeType); writeLong(sizeBytes); writeInt(if (descriptor == null) 0 else 1)
        descriptor?.writeToParcel(this, flags)
        Unit
    }
    override fun describeContents() = if (descriptor == null) 0 else Parcelable.CONTENTS_FILE_DESCRIPTOR
    override fun close() { descriptor?.close() }
    companion object CREATOR : Parcelable.Creator<EvccMusicResource> {
        override fun createFromParcel(parcel: Parcel) = EvccMusicResource(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccMusicResource>(size)
    }
}
