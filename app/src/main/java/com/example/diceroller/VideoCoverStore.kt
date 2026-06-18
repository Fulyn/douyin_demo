package com.example.diceroller

import android.content.res.AssetFileDescriptor
import android.content.res.Resources
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class VideoCoverStore(
    private val resources: Resources,
    private val onCoverReady: (rawResId: Int, cover: Bitmap) -> Unit
) {

    private val coverBitmapsByRawResId = mutableMapOf<Int, Bitmap>()
    private val coverPositionsByRawResId = mutableMapOf<Int, Long>()
    private val coverRequestsInFlight = mutableSetOf<Int>()
    private val coverFrameExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var released = false

    fun coverFor(rawResId: Int): Bitmap? {
        return coverBitmapsByRawResId[rawResId]
    }

    fun requestDefaultCover(rawResId: Int) {
        requestCover(rawResId, DEFAULT_COVER_POSITION_MS)
    }

    fun requestCover(rawResId: Int, positionMs: Long) {
        if (released) return

        val safePositionMs = positionMs.coerceAtLeast(0L)
        val lastCoverPosition = coverPositionsByRawResId[rawResId]
        val coverIsFreshEnough = lastCoverPosition != null &&
            abs(lastCoverPosition - safePositionMs) < COVER_REFRESH_DISTANCE_MS

        if (coverIsFreshEnough || coverRequestsInFlight.contains(rawResId)) return

        coverRequestsInFlight.add(rawResId)
        val requestedPositionMs = safePositionMs

        coverFrameExecutor.execute {
            val frameBitmap = extractFrame(rawResId, requestedPositionMs)

            mainHandler.post {
                coverRequestsInFlight.remove(rawResId)

                val newCover = frameBitmap ?: return@post
                if (released) {
                    newCover.recycle()
                    return@post
                }

                val oldCover = coverBitmapsByRawResId.put(rawResId, newCover)
                coverPositionsByRawResId[rawResId] = requestedPositionMs
                onCoverReady(rawResId, newCover)
                oldCover?.recycle()
            }
        }
    }

    fun release() {
        if (released) return

        released = true
        mainHandler.removeCallbacksAndMessages(null)
        coverFrameExecutor.shutdownNow()
        coverRequestsInFlight.clear()
        coverBitmapsByRawResId.values.forEach { it.recycle() }
        coverBitmapsByRawResId.clear()
        coverPositionsByRawResId.clear()
    }

    private fun extractFrame(rawResId: Int, positionMs: Long): Bitmap? {
        var assetFileDescriptor: AssetFileDescriptor? = null
        var frameBitmap: Bitmap? = null
        val retriever = MediaMetadataRetriever()

        try {
            assetFileDescriptor = resources.openRawResourceFd(rawResId)
            retriever.setDataSource(
                assetFileDescriptor.fileDescriptor,
                assetFileDescriptor.startOffset,
                assetFileDescriptor.length
            )

            val rawFrame = retriever.getFrameAtTime(
                positionMs * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            frameBitmap = rawFrame?.let { scaleDownIfNeeded(it) }
        } catch (_: Exception) {
            frameBitmap?.recycle()
            frameBitmap = null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
            try {
                assetFileDescriptor?.close()
            } catch (_: Exception) {
            }
        }

        return frameBitmap
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= COVER_MAX_SIZE_PX) return bitmap

        val scale = COVER_MAX_SIZE_PX.toFloat() / maxSide
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt(),
            (bitmap.height * scale).roundToInt(),
            true
        ).also {
            bitmap.recycle()
        }
    }

    private companion object {
        const val DEFAULT_COVER_POSITION_MS = 500L
        const val COVER_MAX_SIZE_PX = 720
        const val COVER_REFRESH_DISTANCE_MS = 1_000L
    }
}
