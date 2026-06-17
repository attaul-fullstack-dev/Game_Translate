package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

class ScreenCaptureManager(private val context: Context) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureWidth = 0
    private var captureHeight = 0
    private var physicalWidth = 0
    private var physicalHeight = 0

    fun start(resultCode: Int, data: Intent, width: Int, height: Int, density: Int) {
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // Ambil resolusi fisik layar
        val metrics = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        physicalWidth = metrics.widthPixels
        physicalHeight = metrics.heightPixels

        // Gunakan resolusi fisik untuk capture agar koordinat match 1:1
        captureWidth = physicalWidth
        captureHeight = physicalHeight

        @SuppressLint("WrongConstant")
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            captureWidth, captureHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        Log.d("ScreenCapture", "Started: capture=${captureWidth}x${captureHeight}, physical=${physicalWidth}x${physicalHeight}")
    }

    fun captureRect(x: Int, y: Int, w: Int, h: Int): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return null

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * captureWidth

            val fullBitmap = Bitmap.createBitmap(
                captureWidth + rowPadding / pixelStride,
                captureHeight,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            // Koordinat sudah absolut dari getLocationOnScreen, langsung pakai
            val safeX = maxOf(0, x)
            val safeY = maxOf(0, y)
            val safeW = minOf(w, captureWidth - safeX)
            val safeH = minOf(h, captureHeight - safeY)

            if (safeW <= 0 || safeH <= 0) {
                Log.e("ScreenCapture", "Invalid rect: x=$safeX y=$safeY w=$safeW h=$safeH")
                return null
            }

            Log.d("ScreenCapture", "Cropping: x=$safeX y=$safeY w=$safeW h=$safeH")
            return Bitmap.createBitmap(fullBitmap, safeX, safeY, safeW, safeH)

        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error capturing screen", e)
            return null
        } finally {
            image?.close()
        }
    }

    fun stop() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }
}
