package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class OverlayService : Service() {

    companion object {
        // Direction of the portrait→landscape mapping. Default CCW matches the
        // on-device measurement. Flip to true if capture/overlay land 180°-off
        // or mirrored on the target device.
        private const val CLOCKWISE = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var ocrManager: OcrManager
    private lateinit var translateManager: TranslateManager

    private var rectangleSelectorView: RectangleSelectorView? = null
    private var translationOverlayView: TranslationOverlayView? = null
    private var translationOverlayParams: WindowManager.LayoutParams? = null
    private var controlBarView: ControlBarView? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var captureJob: Job? = null

    private var currentState = OverlayState.IDLE
    // selectedArea is stored in PORTRAIT window space (the selector/overlay live in
    // the system's portrait orientation). The game is force-landscape, so the
    // MediaProjection bitmap is in LANDSCAPE space. captureRect needs the rect
    // mapped portrait→landscape; the overlay keeps the original portrait coords.
    private var selectedArea: IntArray? = null

    override fun onCreate() {
        super.onCreate()
        DebugStore.serviceState.value = "STARTING"
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        screenCaptureManager = ScreenCaptureManager(this)
        ocrManager = OcrManager()
        translateManager = TranslateManager()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugStore.serviceState.value = "RUNNING"
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        if (intent != null && intent.hasExtra("RESULT_CODE")) {
            val resultCode = intent.getIntExtra("RESULT_CODE", 0)
            val data = intent.getParcelableExtra<Intent>("DATA")
            val width = intent.getIntExtra("WIDTH", 720)
            val height = intent.getIntExtra("HEIGHT", 1280)
            val density = intent.getIntExtra("DENSITY", 1)

            if (data != null) {
                screenCaptureManager.start(resultCode, data, width, height, density)
                setupControlBar()
            }
        }
        return START_NOT_STICKY
    }

    private fun getLayoutFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun setupControlBar() {
        controlBarView = ControlBarView(this, windowManager) {
            handleBubbleTap()
        }
        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getLayoutFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            this.x = 16
        }
        controlBarView?.params = controlParams
        windowManager.addView(controlBarView, controlParams)
    }

    private fun handleBubbleTap() {
        when (currentState) {
            OverlayState.IDLE -> startSelectionMode()
            OverlayState.ACTIVE -> {
                currentState = OverlayState.PAUSED
                controlBarView?.updateState(currentState)
                translationOverlayView?.setText("")
            }
            OverlayState.PAUSED -> {
                currentState = OverlayState.ACTIVE
                controlBarView?.updateState(currentState)
            }
            OverlayState.SELECTING -> cancelSelectionMode()
        }
    }

    private fun startSelectionMode() {
        currentState = OverlayState.SELECTING
        controlBarView?.updateState(currentState)

        rectangleSelectorView = RectangleSelectorView(
            context = this,
            windowManager = windowManager,
            onConfirm = { x, y, w, h ->
                selectedArea = intArrayOf(x, y, w, h)

                DebugStore.selectedAreaX.value = x
                DebugStore.selectedAreaY.value = y
                DebugStore.selectedAreaW.value = w
                DebugStore.selectedAreaH.value = h

                rectangleSelectorView?.let { windowManager.removeView(it) }
                rectangleSelectorView = null

                // Overlay uses ORIGINAL portrait coords (same space as the selector).
                setupTranslationOverlay(x, y, w, h)

                currentState = OverlayState.ACTIVE
                controlBarView?.updateState(currentState)
                startCaptureLoop()
            },
            onCancel = { cancelSelectionMode() }
        )

        val rectParams = WindowManager.LayoutParams(
            800, 300,
            getLayoutFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        rectangleSelectorView?.params = rectParams
        windowManager.addView(rectangleSelectorView, rectParams)
    }

    private fun cancelSelectionMode() {
        rectangleSelectorView?.let { windowManager.removeView(it) }
        rectangleSelectorView = null
        currentState = if (selectedArea != null) OverlayState.PAUSED else OverlayState.IDLE
        controlBarView?.updateState(currentState)
    }

    private fun setupTranslationOverlay(x: Int, y: Int, w: Int, h: Int) {
        translationOverlayView?.let { windowManager.removeView(it) }
        translationOverlayView = TranslationOverlayView(this)

        // Overlay windows are composited in the game's landscape frame, so convert
        // the portrait selection the same way the capture crop is converted.
        val o = toOverlayRect(intArrayOf(x, y, w, h))

        val transParams = WindowManager.LayoutParams(
            o[2],
            WindowManager.LayoutParams.WRAP_CONTENT,
            getLayoutFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
       ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = o[0]
            this.y = o[1]
        }
        translationOverlayParams = transParams
        windowManager.addView(translationOverlayView, transParams)
    }

    private fun startCaptureLoop() {
        if (captureJob?.isActive == true) return

        captureJob = scope.launch(Dispatchers.Default) {
            // Beri waktu user pindah ke game
            delay(2000)

            while (isActive) {
                if (currentState == OverlayState.ACTIVE) {
                    // Map the portrait selection into the landscape bitmap space.
                    val area = selectedArea?.let { toBitmapRect(it) }
                    if (area != null && area[2] > 0 && area[3] > 0) {
                        try {
                            val x = area[0]
                            val y = area[1]
                            val w = area[2]
                            val h = area[3]

                            DebugStore.captureStatus.value = "CAPTURING..."
                            val bitmap = screenCaptureManager.captureRect(x, y, w, h)

                            if (bitmap != null) {
                                DebugStore.captureStatus.value = "OK"
                                DebugStore.bitmapCaptured.value = true
                                DebugStore.lastBitmap.value = bitmap

                                val text = ocrManager.extractText(bitmap)
                                DebugStore.ocrRawText.value = text
                                DebugStore.ocrTextLength.value = text.length

                                if (text.isNotBlank()) {
                                    if (DebugStore.enableTranslation.value) {
                                        val translated = translateManager.translate(text)
                                        DebugStore.translationResult.value = translated ?: "NULL"
                                        withContext(Dispatchers.Main) {
                                            translationOverlayView?.setText(
                                                if (!translated.isNullOrBlank()) translated else ""
                                            )
                                        }
                                    } else {
                                        DebugStore.translationResult.value = "SKIPPED (OCR ONLY)"
                                        withContext(Dispatchers.Main) {
                                            translationOverlayView?.setText(text)
                                        }
                                    }
                                } else {
                                    DebugStore.translationResult.value = ""
                                    withContext(Dispatchers.Main) { translationOverlayView?.setText("") }
                                }
                            } else {
                                DebugStore.captureStatus.value = "FAIL"
                                DebugStore.bitmapCaptured.value = false
                            }
                        } catch (e: Exception) {
                            DebugStore.logError(e)
                            e.printStackTrace()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { translationOverlayView?.setText("") }
                }
                delay(1500)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugStore.serviceState.value = "STOPPED"
        captureJob?.cancel()
        scope.cancel()

        rectangleSelectorView?.let { windowManager.removeView(it) }
        translationOverlayView?.let { windowManager.removeView(it) }
        controlBarView?.let { windowManager.removeView(it) }

        screenCaptureManager.stop()
        ocrManager.close()
        translateManager.close()
    }

    /** Device portrait dimensions as (width, height); system stays portrait. */
    private fun portraitSize(): Pair<Float, Float> {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(m)
        return Pair(
            minOf(m.widthPixels, m.heightPixels).toFloat(),  // portrait width
            maxOf(m.widthPixels, m.heightPixels).toFloat()   // portrait height
        )
    }

    /**
     * Rotate a rect from PORTRAIT window space into the force-landscape frame
     * (size ph x pw). Used by BOTH the overlay placement and the capture crop so
     * the two can never diverge. Result is [x, y, w, h] as floats.
     *
     * Default is 90° CCW, matching the on-device measurement
     * (lx = y, ly = portraitWidth - x - w). If captures/overlay land 180°-off or
     * mirrored, flip CLOCKWISE to true.
     */
    private fun rotatePortraitToLandscape(a: IntArray): FloatArray {
        val (pw, ph) = portraitSize()
        val x = a[0]; val y = a[1]; val w = a[2]; val h = a[3]
        val rx: Float; val ry: Float
        if (CLOCKWISE) {
            rx = ph - (y + h)
            ry = x.toFloat()
        } else {
            rx = y.toFloat()
            ry = pw - (x + w)
        }
        return floatArrayOf(rx, ry, h.toFloat(), w.toFloat())
    }

    /** Overlay window position in the landscape frame (no scaling — window space). */
    private fun toOverlayRect(a: IntArray): IntArray {
        val r = rotatePortraitToLandscape(a)
        return intArrayOf(r[0].toInt(), r[1].toInt(), r[2].toInt(), r[3].toInt())
    }

    /**
     * Capture crop in ACTUAL bitmap pixels: same rotation as the overlay, then
     * scaled from the portrait-derived landscape frame (ph x pw) to the real
     * bitmap size in case they differ (nav bar, rounding, etc.).
     */
    private fun toBitmapRect(a: IntArray): IntArray {
        val (bmpW, bmpH) = screenCaptureManager.bitmapSize() ?: return a
        val (pw, ph) = portraitSize()
        val r = rotatePortraitToLandscape(a)
        val sx = bmpW / ph
        val sy = bmpH / pw
        return intArrayOf(
            (r[0] * sx).toInt(),
            (r[1] * sy).toInt(),
            (r[2] * sx).toInt(),
            (r[3] * sy).toInt()
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "game_translator_channel",
                "Game Translator Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "game_translator_channel")
            .setContentTitle("Game Translator Active")
            .setContentText("Translating screen text...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}
