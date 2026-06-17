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
import android.util.Log

class ScreenCaptureManager(private val context: Context) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var screenWidth = 0
    private var screenHeight = 0
    private var scaleX = 1f
    private var scaleY = 1f
    
    fun start(resultCode: Int, data: Intent, width: Int, height: Int, density: Int) {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        screenWidth = width
        screenHeight = height

        val realMetrics = android.util.DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealMetrics(realMetrics)
        scaleX = width.toFloat() / realMetrics.widthPixels
        scaleY = height.toFloat() / realMetrics.heightPixels
        
        @SuppressLint("WrongConstant")
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    fun captureRect(x: Int, y: Int, w: Int, h: Int): Bitmap? {
        val imageReaderParam = imageReader ?: return null
        var image: Image? = null
        try {
            image = imageReaderParam.acquireLatestImage()
            if (image == null) return null

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            
            // Adjust bounds to avoid crashing if rectangle goes out of screen
            val safeX = maxOf(0, (x * scaleX).toInt())
            val safeY = maxOf(0, (y * scaleY).toInt())
            val safeW = minOf((w * scaleX).toInt(), screenWidth - safeX)
            val safeH = minOf((h * scaleY).toInt(), screenHeight - safeY)

            if (safeW <= 0 || safeH <= 0) return null

            return Bitmap.createBitmap(bitmap, safeX, safeY, safeW, safeH)
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
