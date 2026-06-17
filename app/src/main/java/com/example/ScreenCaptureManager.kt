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

    private val handlerThread = HandlerThread("ScreenCaptureThread").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    @Volatile
    private var latestBitmap: Bitmap? = null

    fun start(resultCode: Int, data: Intent, width: Int, height: Int, density: Int) {
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels

        @SuppressLint("WrongConstant")
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)

        // Pakai callback, bukan polling
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * captureWidth

                val bmp = Bitmap.createBitmap(
                    captureWidth + rowPadding / pixelStride,
                    captureHeight,
                    Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                latestBitmap = bmp
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            captureWidth, captureHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        Log.d("ScreenCapture", "Started: ${captureWidth}x${captureHeight}")
    }

    fun captureRect(x: Int, y: Int, w: Int, h: Int): Bitmap? {
        val full = latestBitmap ?: return null

        val safeX = maxOf(0, x)
        val safeY = maxOf(0, y)
        val safeW = minOf(w, captureWidth - safeX)
        val safeH = minOf(h, captureHeight - safeY)

        if (safeW <= 0 || safeH <= 0) {
            Log.e("ScreenCapture", "Invalid rect: x=$safeX y=$safeY w=$safeW h=$safeH")
            return null
        }

        return Bitmap.createBitmap(full, safeX, safeY, safeW, safeH)
    }

    fun stop() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        handlerThread.quitSafely()

        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        latestBitmap = null
    }
}
