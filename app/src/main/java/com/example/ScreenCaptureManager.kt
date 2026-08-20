package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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

class ScreenCaptureManager(
    private val context: Context,
    private val onProjectionStopped: () -> Unit = {},
) {
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
                    resizeVirtualDisplay(width, height, context.resources.displayMetrics.densityDpi)
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), context.resources.displayMetrics.densityDpi)
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    private fun resizeVirtualDisplay(width: Int, height: Int, density: Int) {
        if (stopped || width <= 0 || height <= 0) return
        if (virtualDisplay != null &&
            captureWidth == width &&
            captureHeight == height &&
            densityDpi == density
        ) return

        val projection = mediaProjection ?: return
        val newReader = createImageReader(width, height)

        synchronized(captureLock) {
            if (stopped) {
                newReader.close()
                return
            }

            val oldReader = imageReader
            captureWidth = width
            captureHeight = height
            densityDpi = density
            imageReader = newReader
            latestBitmap?.recycle()
            latestBitmap = null

            val display = virtualDisplay
            if (display == null) {
                virtualDisplay = projection.createVirtualDisplay(
                    "ScreenCapture",
                    captureWidth,
                    captureHeight,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    newReader.surface,
                    null,
                    handler,
                ) ?: run {
                    imageReader = null
                    newReader.close()
                    throw IllegalStateException("Unable to create screen capture display")
                }
            } else {
                display.resize(captureWidth, captureHeight, densityDpi)
                display.setSurface(newReader.surface)
                oldReader?.close()
            }

            Log.d("ScreenCapture", "Capture surface ready: ${captureWidth}x${captureHeight}")
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
                    val planes = image.planes
                    val buffer = planes[0].buffer.also { it.rewind() }
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride

                    // Width derived from rowStride/pixelStride (handles row padding).
                    val bmpWidth = rowStride / pixelStride

                    // Guard against a buffer/bitmap size mismatch BEFORE the native
                    // copy — a too-small buffer is the other path to a SIGSEGV.
                    val needed = bmpWidth * height * pixelStride
                    if (buffer.remaining() < needed) {
                        Log.w("ScreenCapture", "Buffer too small: have=${buffer.remaining()} need=$needed")
                        return@synchronized
                    }

                    var frame = latestBitmap
                    if (frame == null || frame.width != bmpWidth || frame.height != height) {
                        frame?.recycle()
                        frame = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
                        latestBitmap = frame
                    }
                    frame.copyPixelsFromBuffer(buffer)
                } catch (e: Throwable) {
                    // Never let the capture thread crash the process.
                    DebugStore.logError(if (e is Exception) e else RuntimeException(e))
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
        }
    }

    /** Actual size of the most recent captured frame (landscape for a force-landscape game), or null. */
    fun bitmapSize(): Pair<Int, Int>? {
        synchronized(captureLock) {
            if (latestBitmap == null) return null
            return Pair(captureWidth, captureHeight)
        }
    }

    fun captureRect(x: Int, y: Int, w: Int, h: Int): Bitmap? {
        synchronized(captureLock) {
            val full = latestBitmap ?: return null

            // Ignore row padding in the reusable backing bitmap.
            val bmpW = captureWidth.coerceAtMost(full.width)
            val bmpH = captureHeight.coerceAtMost(full.height)

            val safeX = x.coerceIn(0, maxOf(0, bmpW - 1))
            val safeY = y.coerceIn(0, maxOf(0, bmpH - 1))
            val safeW = minOf(w, bmpW - safeX)
            val safeH = minOf(h, bmpH - safeY)

            if (safeW <= 0 || safeH <= 0) {
                Log.e("ScreenCapture", "Invalid rect: x=$safeX y=$safeY w=$safeW h=$safeH bmp=${bmpW}x${bmpH}")
                return null
            }

            val cropped = Bitmap.createBitmap(full, safeX, safeY, safeW, safeH)
            // Bitmap.createBitmap may return the source for a full-frame crop. The
            // source is reused by the capture callback, so callers need a snapshot.
            return if (cropped === full) {
                full.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                cropped
            }
        }
    }

    fun stop() {
        // Set stopped first so an in-flight callback bails, then take the lock to
        // ensure no copy is in progress before we free native buffers.
        stopped = true
        synchronized(captureLock) {
            mediaProjection?.unregisterCallback(projectionCallback)
            releaseFrameResourcesLocked()
            mediaProjection?.stop()
            mediaProjection = null
        }
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
    }

    private fun releaseFrameResourcesLocked() {
        virtualDisplay?.release()
        imageReader?.close()
        latestBitmap?.recycle()
        virtualDisplay = null
        imageReader = null
        latestBitmap = null
        captureWidth = 0
        captureHeight = 0
    }
}
