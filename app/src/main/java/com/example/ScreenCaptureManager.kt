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

        // Release previous resources before recreating.
        virtualDisplay?.release()
        imageReader?.close()
        latestBitmap = null

        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride

                // Width must be derived from rowStride/pixelStride, not
                // captureWidth + rowPadding/pixelStride: the latter truncates when
                // rowPadding isn't an exact multiple of pixelStride, producing a
                // bitmap one pixel too narrow → "Buffer not large enough for pixels".
                val bmpWidth = rowStride / pixelStride
                val padded = Bitmap.createBitmap(
                    bmpWidth,
                    captureHeight,
                    Bitmap.Config.ARGB_8888
                )
                padded.copyPixelsFromBuffer(buffer)

                // Trim the padding so latestBitmap is EXACTLY captureWidth x captureHeight.
                // Crop coordinates assume an unpadded screen-sized bitmap.
                latestBitmap = if (padded.width != captureWidth) {
                    val trimmed = Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight)
                    padded.recycle()
                    trimmed
                } else {
                    padded
                }
            } catch (e: Exception) {
                DebugStore.logError(e)
            } finally {
                image.close()
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
        stopped = true
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        handlerThread.quitSafely()

        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        latestBitmap = null
    }
}
