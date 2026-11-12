package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

/** API 1.1. Fixed wire order; extend with a new type, never append fields. */
data class EvccMusicState @JvmOverloads constructor(
    val status: String = EvccApiContract.Status.UNAVAILABLE,
    val message: String = "",
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val sourcePackage: String = "",
    val sourceAppName: String = "",
    val playbackState: String = "NONE",
    val positionMs: Long = -1L,
    val positionAtElapsedMs: Long = 0L,
    val durationMs: Long = -1L,
    val playbackSpeed: Float = 0f,
    val currentLyric: String = "",
    val lineStartMs: Long = -1L,
    val lineEndMs: Long = -1L,
    val lyricOffsetMs: Long = 0L,
    val lyricsStatus: String = EvccApiContract.Status.NOT_FOUND,
    val lyricsFormat: String = "none",
    val lyricsRevision: Long = 0L,
    val artworkStatus: String = EvccApiContract.Status.NOT_FOUND,
    val artworkRevision: Long = 0L,
    val favoriteKnown: Boolean = false,
    val favorite: Boolean = false,
    val wholeBpm: Float = 0f,
    val currentBpm: Float = 0f,
    val bpmStatus: String = "unknown",
    val bpmSegmentStartMs: Long = -1L,
    val bpmSegmentEndMs: Long = -1L,
    val supportedCommands: List<String> = emptyList(),
    val textTruncated: Boolean = false,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readLong(),
        parcel.readLong(),
        parcel.readLong(),
        parcel.readFloat(),
        parcel.readString().orEmpty(),
        parcel.readLong(),
        parcel.readLong(),
        parcel.readLong(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readLong(),
        parcel.readString().orEmpty(),
        parcel.readLong(),
        parcel.readInt() != 0,
        parcel.readInt() != 0,
        parcel.readFloat(),
        parcel.readFloat(),
        parcel.readString().orEmpty(),
        parcel.readLong(),
        parcel.readLong(),
        parcel.createStringArrayList().orEmpty(),
        parcel.readInt() != 0,
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(status)
        writeString(message)
        writeString(trackId)
        writeString(title)
        writeString(artist)
        writeString(album)
        writeString(sourcePackage)
        writeString(sourceAppName)
        writeString(playbackState)
        writeLong(positionMs)
        writeLong(positionAtElapsedMs)
        writeLong(durationMs)
        writeFloat(playbackSpeed)
        writeString(currentLyric)
        writeLong(lineStartMs)
        writeLong(lineEndMs)
        writeLong(lyricOffsetMs)
        writeString(lyricsStatus)
        writeString(lyricsFormat)
        writeLong(lyricsRevision)
        writeString(artworkStatus)
        writeLong(artworkRevision)
        writeInt(if (favoriteKnown) 1 else 0)
        writeInt(if (favorite) 1 else 0)
        writeFloat(wholeBpm)
        writeFloat(currentBpm)
        writeString(bpmStatus)
        writeLong(bpmSegmentStartMs)
        writeLong(bpmSegmentEndMs)
        writeStringList(supportedCommands)
        writeInt(if (textTruncated) 1 else 0)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccMusicState> {
        override fun createFromParcel(parcel: Parcel) = EvccMusicState(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccMusicState>(size)
    }
}
