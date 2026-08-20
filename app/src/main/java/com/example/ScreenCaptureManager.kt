package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlin.math.sqrt

internal data class FrameRequest(
    val baseline: Long,
    val target: Long,
)

class ScreenCaptureManager(
    private val context: Context,
    private val onProjectionStopped: () -> Unit = {},
) {
    private companion object {
        const val MAX_OCR_BITMAP_PIXELS = 1_500_000L
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureWidth = 0
    private var captureHeight = 0
    private var densityDpi = 0

    private val handlerThread = HandlerThread("ScreenCaptureThread").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    @Volatile
    private var latestBitmap: Bitmap? = null

    @Volatile
    private var stopped = false

    @Volatile
    private var frameSequence = 0L
    private var requestedFrameSequence = 0L

    // Serializes the image-available callback (ScreenCaptureThread) against teardown
    // (close/release on another thread). Without this, closing the ImageReader frees
    // the native image buffer while copyPixelsFromBuffer is still reading it → SIGSEGV.
    private val captureLock = Any()

    // Mandatory on targetSdk 34+: createVirtualDisplay() throws IllegalStateException
    // if no callback is registered before it is called.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w("ScreenCapture", "MediaProjection stopped by system")
            DebugStore.captureStatus.value = "PROJECTION_STOPPED"
            synchronized(captureLock) {
                stopped = true
                releaseFrameResourcesLocked()
                mediaProjection = null
            }
            onProjectionStopped()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && width > 0 && height > 0) {
                try {
                    val (limitedWidth, limitedHeight) = CaptureSizeLimiter.limit(width, height)
                    resizeVirtualDisplay(
                        limitedWidth,
                        limitedHeight,
                        context.resources.configuration.densityDpi,
                    )
                } catch (_: OutOfMemoryError) {
                    failForLowMemory()
                } catch (e: Exception) {
                    DebugStore.logError(e)
                }
            }
        }
    }

    fun start(resultCode: Int, data: Intent) {
        check(mediaProjection == null) { "Screen capture session is already active" }
        stopped = false
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("Unable to start screen capture")

        // Register callback BEFORE createVirtualDisplay (required on Android 14+).
        mediaProjection?.registerCallback(projectionCallback, handler)

        val (width, height, density) = currentDisplayGeometry()
        resizeVirtualDisplay(width, height, density)
    }

    /**
     * Updates the capture surface to the current display size. A MediaProjection
     * token may create only one VirtualDisplay on Android 14+, so rotations use
     * VirtualDisplay.resize() + setSurface() instead of creating another display.
     */
    @SuppressLint("WrongConstant")
    fun refreshDisplayGeometry() {
        if (stopped) return
        val (width, height, density) = currentDisplayGeometry()
        try {
            resizeVirtualDisplay(width, height, density)
        } catch (e: Exception) {
            DebugStore.logError(e)
        }
    }

    private fun currentDisplayGeometry(): Triple<Int, Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            metrics.widthPixels to metrics.heightPixels
        }
        val (limitedWidth, limitedHeight) = CaptureSizeLimiter.limit(width, height)
        return Triple(
            limitedWidth,
            limitedHeight,
            context.resources.configuration.densityDpi,
        )
    }

    private fun resizeVirtualDisplay(width: Int, height: Int, density: Int) {
        if (stopped || width <= 0 || height <= 0) return

        synchronized(captureLock) {
            if (stopped) return
            if (virtualDisplay != null &&
                captureWidth == width &&
                captureHeight == height &&
                densityDpi == density
            ) return

            val projection = mediaProjection ?: return
            val display = virtualDisplay
            val oldReader = imageReader
            val oldWidth = captureWidth
            val oldHeight = captureHeight
            val oldDensity = densityDpi
            val newReader = try {
                createImageReader(width, height)
            } catch (failure: OutOfMemoryError) {
                failForLowMemory()
                throw IllegalStateException("Not enough memory for screen capture", failure)
            }

            val readyDisplay = try {
                if (display == null) {
                    projection.createVirtualDisplay(
                        "ScreenCapture",
                        width,
                        height,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        newReader.surface,
                        null,
                        handler,
                    ) ?: throw IllegalStateException("Unable to create screen capture display")
                } else {
                    display.resize(width, height, density)
                    display.setSurface(newReader.surface)
                    display
                }
            } catch (failure: Throwable) {
                var rollbackFailed = false
                if (display != null &&
                    oldReader != null &&
                    oldWidth > 0 &&
                    oldHeight > 0 &&
                    oldDensity > 0
                ) {
                    try {
                        display.resize(oldWidth, oldHeight, oldDensity)
                        display.setSurface(oldReader.surface)
                    } catch (rollbackFailure: Throwable) {
                        rollbackFailed = true
                        failure.addSuppressed(rollbackFailure)
                    }
                }
                try {
                    newReader.close()
                } catch (closeFailure: RuntimeException) {
                    failure.addSuppressed(closeFailure)
                }
                if (rollbackFailed) {
                    failCaptureSession(
                        "Screen capture dihentikan karena resize display gagal dipulihkan.",
                    )
                }
                if (failure is OutOfMemoryError) {
                    failForLowMemory()
                    throw IllegalStateException("Not enough memory for screen capture", failure)
                }
                throw failure
            }

            // Commit only after both resize and surface replacement succeed.
            virtualDisplay = readyDisplay
            imageReader = newReader
            captureWidth = width
            captureHeight = height
            densityDpi = density
            requestedFrameSequence = frameSequence
            latestBitmap?.recycle()
            latestBitmap = null
            try {
                oldReader?.close()
            } catch (failure: RuntimeException) {
                DebugStore.logError(failure)
            }

            Log.d(
                "ScreenCapture",
                "Capture surface ready: " + captureWidth + "x" + captureHeight,
            )
        }
    }

    private fun createImageReader(width: Int, height: Int): ImageReader {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            // Hold the lock for the WHOLE callback so teardown (which also takes the
            // lock) cannot free the native buffer mid-copy. Bail early if stopped.
            synchronized(captureLock) {
                if (stopped) {
                    // Drain so the reader doesn't stall, but don't touch the buffer.
                    try { r.acquireLatestImage()?.close() } catch (_: Throwable) {}
                    return@synchronized
                }
                if (r !== imageReader) {
                    try { r.acquireLatestImage()?.close() } catch (_: Throwable) {}
                    return@synchronized
                }
                var image: android.media.Image? = null
                try {
                    image = r.acquireLatestImage() ?: return@synchronized
                    if (frameSequence >= requestedFrameSequence) return@synchronized

                    val planes = image.planes
                    val buffer = planes[0].buffer.also { it.rewind() }
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride

                    // Width derived from rowStride/pixelStride (handles row padding).
                    val bmpWidth = rowStride / pixelStride

                    // Guard against a buffer/bitmap size mismatch BEFORE the native
                    // copy — a too-small buffer is the other path to a SIGSEGV.
                    val needed = bmpWidth.toLong() * height.toLong() * pixelStride.toLong()
                    if (buffer.remaining().toLong() < needed) {
                        Log.w(
                            "ScreenCapture",
                            "Buffer too small: have=" + buffer.remaining() + " need=" + needed,
                        )
                        return@synchronized
                    }

                    var frame = latestBitmap
                    if (frame == null || frame.width != bmpWidth || frame.height != height) {
                        frame?.recycle()
                        frame = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
                        latestBitmap = frame
                    }
                    frame.copyPixelsFromBuffer(buffer)
                    frameSequence++
                } catch (_: OutOfMemoryError) {
                    failForLowMemory()
                } catch (e: Exception) {
                    // Never let the capture thread crash the process.
                    DebugStore.logError(e)
                } finally {
                    image?.close()
                }
            }
        }, handler)
        return reader
    }

    fun resetBitmap() {
        synchronized(captureLock) {
            latestBitmap?.recycle()
            latestBitmap = null
            requestedFrameSequence = frameSequence
        }
    }

    /** Actual size of the most recent captured frame (landscape for a force-landscape game), or null. */
    fun bitmapSize(): Pair<Int, Int>? {
        synchronized(captureLock) {
            if (latestBitmap == null) return null
            return Pair(captureWidth, captureHeight)
        }
    }

    fun frameSequence(): Long = frameSequence

    /** Requests one or more snapshots while still draining unused display frames. */
    internal fun requestFreshFrames(count: Int = 1): FrameRequest? {
        require(count > 0) { "Frame request count must be positive" }
        synchronized(captureLock) {
            if (stopped || imageReader == null) return null
            val baseline = frameSequence
            val target = baseline + count
            requestedFrameSequence = maxOf(requestedFrameSequence, target)
            return FrameRequest(baseline = baseline, target = target)
        }
    }

    fun cancelPendingFrameRequests() {
        synchronized(captureLock) {
            requestedFrameSequence = frameSequence
        }
    }

    fun captureRect(x: Int, y: Int, w: Int, h: Int): Bitmap? {
        return try {
            synchronized(captureLock) {
                val full = latestBitmap ?: return@synchronized null

                // Ignore row padding in the reusable backing bitmap.
                val bmpW = captureWidth.coerceAtMost(full.width)
                val bmpH = captureHeight.coerceAtMost(full.height)

                val safeX = x.coerceIn(0, maxOf(0, bmpW - 1))
                val safeY = y.coerceIn(0, maxOf(0, bmpH - 1))
                val safeW = minOf(w, bmpW - safeX)
                val safeH = minOf(h, bmpH - safeY)

                if (safeW <= 0 || safeH <= 0) {
                    Log.e(
                        "ScreenCapture",
                        "Invalid rect: x=" + safeX + " y=" + safeY +
                            " w=" + safeW + " h=" + safeH +
                            " bmp=" + bmpW + "x" + bmpH,
                    )
                    return@synchronized null
                }

                val pixelCount = safeW.toLong() * safeH.toLong()
                val scale = if (pixelCount > MAX_OCR_BITMAP_PIXELS) {
                    sqrt(MAX_OCR_BITMAP_PIXELS.toDouble() / pixelCount.toDouble()).toFloat()
                } else {
                    1f
                }
                val matrix = Matrix().apply { setScale(scale, scale) }
                val cropped = Bitmap.createBitmap(
                    full,
                    safeX,
                    safeY,
                    safeW,
                    safeH,
                    matrix,
                    scale < 1f,
                )
                // Bitmap.createBitmap may return the source for a full-frame crop.
                // The source is reused by the capture callback, so return a snapshot.
                if (cropped === full) {
                    full.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    cropped
                }
            }
        } catch (_: OutOfMemoryError) {
            failForLowMemory()
            null
        }
    }

    fun stop() {
        // Set stopped first so an in-flight callback bails, then take the lock to
        // ensure no copy is in progress before we free native buffers.
        stopped = true
        synchronized(captureLock) {
            val projection = mediaProjection
            try {
                projection?.unregisterCallback(projectionCallback)
            } catch (failure: RuntimeException) {
                DebugStore.logError(failure)
            }
            releaseFrameResourcesLocked()
            mediaProjection = null
            try {
                projection?.stop()
            } catch (failure: RuntimeException) {
                DebugStore.logError(failure)
            }
        }
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
    }

    private fun releaseFrameResourcesLocked() {
        try {
            virtualDisplay?.release()
        } catch (failure: RuntimeException) {
            DebugStore.logError(failure)
        }
        try {
            imageReader?.close()
        } catch (failure: RuntimeException) {
            DebugStore.logError(failure)
        }
        try {
            latestBitmap?.recycle()
        } catch (failure: RuntimeException) {
            DebugStore.logError(failure)
        }
        virtualDisplay = null
        imageReader = null
        latestBitmap = null
        captureWidth = 0
        captureHeight = 0
        requestedFrameSequence = frameSequence
    }

    private fun failForLowMemory() {
        failCaptureSession("Screen capture dihentikan: memori perangkat tidak mencukupi.")
    }

    private fun failCaptureSession(message: String) {
        if (stopped) return
        stopped = true
        DebugStore.logMessage(message)
        onProjectionStopped()
    }
}
