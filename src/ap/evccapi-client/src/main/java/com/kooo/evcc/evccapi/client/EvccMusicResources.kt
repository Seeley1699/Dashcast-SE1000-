package com.kooo.evcc.evccapi.client

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.kooo.evcc.evccapi.*
import java.io.InputStream
import java.io.IOException

/** Resource decoding is deliberately outside Binder callback threads. */
object EvccMusicResources {
    data class Lyrics(val status: String, val trackId: String, val revision: Long, val text: String)

    @JvmStatic fun readLyrics(resource: EvccMusicResource): Lyrics = resource.use {
        if (it.status != EvccApiContract.Status.AVAILABLE || it.descriptor == null) {
            return Lyrics(if (it.status == EvccApiContract.Status.AVAILABLE) EvccApiContract.Status.FAILED else it.status, it.trackId, it.revision, "")
        }
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(it.descriptor).use(::readBounded)
        Lyrics(it.status, it.trackId, it.revision, Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString())
    }

    /** Caller owns the returned stream. A failed resource is closed before throwing. */
    @JvmStatic fun openStream(resource: EvccMusicResource): InputStream {
        val descriptor = resource.descriptor
        if (resource.status != EvccApiContract.Status.AVAILABLE || descriptor == null ||
            resource.sizeBytes !in 0..EvccApiContract.MAX_MUSIC_RESOURCE_BYTES.toLong()) {
            resource.close()
            throw IOException("Music resource: ${resource.status}")
        }
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    }

    /** Bounded thumbnail decode; owns and closes the supplied resource. */
    @JvmStatic @JvmOverloads fun readArtwork(resource: EvccMusicResource, maxDimension: Int = 1024): android.graphics.Bitmap? {
        require(maxDimension in 1..2048)
        val bytes = openStream(resource).use(::readBounded)
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Invalid artwork")
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxDimension) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > EvccApiContract.MAX_MUSIC_RESOURCE_BYTES) throw IOException("Music resource too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    @JvmStatic @JvmOverloads fun positionAt(state: EvccMusicState, nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        if (state.positionMs < 0) return -1L
        val base = state.positionMs
        val speed = state.playbackSpeed.takeIf { it.isFinite() && it >= 0 } ?: 0f
        val advancing = state.status == EvccApiContract.Status.AVAILABLE && state.playbackState == "PLAYING" && state.positionAtElapsedMs > 0
        val delta = if (advancing) ((nowElapsedMs - state.positionAtElapsedMs).coerceAtLeast(0).toDouble() * speed).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong() else 0L
        val value = if (delta > Long.MAX_VALUE - base) Long.MAX_VALUE else base + delta
        return if (state.durationMs > 0) value.coerceAtMost(state.durationMs) else value
    }
}
