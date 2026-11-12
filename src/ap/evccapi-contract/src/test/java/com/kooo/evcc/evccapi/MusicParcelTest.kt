package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MusicParcelTest {
    @Test fun stateAndCommandsPreserveEveryFieldAndParcelBoundary() {
        val state = EvccMusicState("AVAILABLE", "message", "track", "title", "artist", "album", "pkg", "app",
            "PLAYING", 11L, 12L, 13L, 1.25f, "line 🎵", 14L, 15L, 16L, "AVAILABLE", "lrc", 17L,
            "AVAILABLE", 18L, true, true, 120f, 125f, "matched", 19L, 20L, listOf("PLAY", "PAUSE"), true)
        val command = EvccMusicCommand("id", "SEEK", 234L, true, "track", true)
        val parcel = Parcel.obtain()
        try {
            state.writeToParcel(parcel, 0)
            command.writeToParcel(parcel, 0)
            parcel.writeInt(0x12345678)
            parcel.setDataPosition(0)
            assertEquals(state, EvccMusicState.CREATOR.createFromParcel(parcel))
            assertEquals(command, EvccMusicCommand.CREATOR.createFromParcel(parcel))
            assertEquals(0x12345678, parcel.readInt())
        } finally { parcel.recycle() }
    }

    @Test fun resourceTransfersReadableDescriptorWithMetadata() {
        val file = File.createTempFile("music-test", ".lrc").apply { writeText("[00:00]歌曲 🎵") }
        val resource = EvccMusicResource("AVAILABLE", "", "track", 12L, "text/plain; charset=utf-8",
            file.length(), ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        val parcel = Parcel.obtain()
        try {
            resource.writeToParcel(parcel, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
            // Match the AIDL return path: it transfers ownership and closes the server descriptor.
            parcel.setDataPosition(0)
            EvccMusicResource.CREATOR.createFromParcel(parcel).use { copy ->
                assertEquals(resource.trackId, copy.trackId)
                assertEquals(resource.revision, copy.revision)
                assertEquals(resource.mimeType, copy.mimeType)
                assertEquals(resource.sizeBytes, copy.sizeBytes)
                assertEquals(1, copy.describeContents())
                assertEquals(file.readText(), ParcelFileDescriptor.AutoCloseInputStream(copy.descriptor).bufferedReader().use { it.readText() })
            }
        } finally { resource.close(); parcel.recycle(); file.delete() }
    }

    @Test fun failedResourcesDoNotRequireADescriptor() {
        val resource = EvccMusicResource(status = EvccApiContract.Status.STALE_RESOURCE, trackId = "old", revision = 7)
        val parcel = Parcel.obtain()
        try {
            resource.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val copy = EvccMusicResource.CREATOR.createFromParcel(parcel)
            assertEquals(resource, copy)
            assertEquals(0, copy.describeContents())
            copy.close()
        } finally { parcel.recycle() }
    }
}
