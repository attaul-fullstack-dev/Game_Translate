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
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

class ScreenCaptureManager(private val context: Context) {
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
            latestBitmap = null
        }
    }

    fun start(resultCode: Int, data: Intent, width: Int, height: Int, density: Int) {
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // Register callback BEFORE createVirtualDisplay (required on Android 14+).
        mediaProjection?.registerCallback(projectionCallback, handler)

        createVirtualDisplay()
    }

    /**
     * (Re)creates the ImageReader + VirtualDisplay at the current real screen size.
     * Must be called again after a rotation so the captured frames match the
     * on-screen orientation (otherwise landscape frames are letterboxed/scaled and
     * crop coordinates no longer line up — Masalah 2).
     */
    @SuppressLint("WrongConstant")
    fun createVirtualDisplay() {
        if (stopped) return
        val projection = mediaProjection ?: return

        val metrics = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        // No-op if size hasn't actually changed and we already have a display.
        if (virtualDisplay != null &&
            captureWidth == metrics.widthPixels &&
            captureHeight == metrics.heightPixels
        ) {
            return
        }

        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        densityDpi = metrics.densityDpi

        // Release previous resources before recreating. Take the lock so we don't
        // close the old reader while its callback is mid-copy (SIGSEGV otherwise).
        synchronized(captureLock) {
            virtualDisplay?.release()
            imageReader?.close()
            virtualDisplay = null
            imageReader = null
            latestBitmap = null
        }

        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            // Hold the lock for the WHOLE callback so teardown (which also takes the
            // lock) cannot free the native buffer mid-copy. Bail early if stopped.
            synchronized(captureLock) {
                if (stopped) {
                    // Drain so the reader doesn't stall, but don't touch the buffer.
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
                    val needed = bmpWidth * captureHeight * pixelStride
                    if (buffer.remaining() < needed) {
                        Log.w("ScreenCapture", "Buffer too small: have=${buffer.remaining()} need=$needed")
                        return@synchronized
                    }

                    val padded = Bitmap.createBitmap(
                        bmpWidth,
                        captureHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    padded.copyPixelsFromBuffer(buffer)

                    // Trim padding so latestBitmap is EXACTLY captureWidth x captureHeight.
                    latestBitmap = if (padded.width != captureWidth) {
                        val trimmed = Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight)
                        padded.recycle()
                        trimmed
                    } else {
                        padded
                    }
                } catch (e: Throwable) {
                    // Never let the capture thread crash the process.
                    DebugStore.logError(if (e is Exception) e else RuntimeException(e))
                } finally {
                    image?.close()
                }
            }
        }, handler)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture",
            captureWidth, captureHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )

        Log.d("ScreenCapture", "VirtualDisplay created: ${captureWidth}x${captureHeight}")
    }

    fun resetBitmap() {
        latestBitmap = null
    }

    /** Actual size of the most recent captured frame (landscape for a force-landscape game), or null. */
    fun bitmapSize(): Pair<Int, Int>? {
        val b = latestBitmap ?: return null
        return Pair(b.width, b.height)
    }

    fun captureRect(x: Int, y: Int, w: Int, h: Int): Bitmap? {
        val full = latestBitmap ?: return null

        // Clamp against the ACTUAL bitmap size, not the cached capture dimensions,
        // so a mid-rotation frame can never produce an out-of-bounds crop.
        val bmpW = full.width
        val bmpH = full.height

        val safeX = x.coerceIn(0, maxOf(0, bmpW - 1))
        val safeY = y.coerceIn(0, maxOf(0, bmpH - 1))
        val safeW = minOf(w, bmpW - safeX)
        val safeH = minOf(h, bmpH - safeY)

        if (safeW <= 0 || safeH <= 0) {
            Log.e("ScreenCapture", "Invalid rect: x=$safeX y=$safeY w=$safeW h=$safeH bmp=${bmpW}x${bmpH}")
            return null
        }

        return Bitmap.createBitmap(full, safeX, safeY, safeW, safeH)
    }

    fun stop() {
        // Set stopped first so an in-flight callback bails, then take the lock to
        // ensure no copy is in progress before we free native buffers.
        stopped = true
        synchronized(captureLock) {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()

            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            latestBitmap = null
        }
        handlerThread.quitSafely()
    }
}
