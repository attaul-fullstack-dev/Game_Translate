package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.*

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.aistudio.gametranslator.action.STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_DATA = "DATA"

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

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var captureJob: Job? = null

    @Volatile
    private var currentState = OverlayState.IDLE
    // selectedArea is stored in PORTRAIT window space (the selector/overlay live in
    // the system's portrait orientation). The game is force-landscape, so the
    // MediaProjection bitmap is in LANDSCAPE space. captureRect needs the rect
    // mapped portrait→landscape; the overlay keeps the original portrait coords.
    @Volatile
    private var selectedArea: IntArray? = null
    private var consecutiveBlankFrames = 0

    override fun onCreate() {
        super.onCreate()
        DebugStore.resetSession()
        DebugStore.serviceState.value = "STARTING"
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        screenCaptureManager = ScreenCaptureManager(this) {
            scope.launch { stopSelf() }
        }
        ocrManager = OcrManager()
        translateManager = TranslateManager()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        DebugStore.serviceState.value = "RUNNING"
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        if (!PermissionHelper.hasOverlayPermission(this)) {
            DebugStore.lastError.value = "Izin overlay tidak tersedia."
            stopSelf()
            return START_NOT_STICKY
        }

        if (controlBarView != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (resultCode == 0 || data == null) {
            DebugStore.lastError.value = "Token screen capture tidak valid."
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            screenCaptureManager.start(resultCode, data)
            setupControlBar()
            updateState(OverlayState.IDLE)
        } catch (e: Exception) {
            DebugStore.logError(e)
            stopSelf()
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
        if (controlBarView != null) return
        controlBarView = ControlBarView(
            context = this,
            windowManager = windowManager,
            onBubbleTap = ::handleBubbleTap,
            onBubbleLongPress = ::reselectArea,
        )
        val bounds = displayBounds()
        val bubbleMargin = dpToPx(16)
        val bubbleSize = dpToPx(48)
        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getLayoutFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (bounds.width() - bubbleSize - bubbleMargin).coerceAtLeast(0)
            this.y = ((bounds.height() - bubbleSize) / 2).coerceAtLeast(0)
        }
        controlBarView?.params = controlParams
        controlBarView?.let { addViewSafely(it, controlParams) }
    }

    private fun handleBubbleTap() {
        when (currentState) {
            OverlayState.IDLE -> startSelectionMode()
            OverlayState.ACTIVE -> {
                updateState(OverlayState.PAUSED)
                translationOverlayView?.setText("")
            }
            OverlayState.PAUSED -> {
                updateState(OverlayState.ACTIVE)
            }
            OverlayState.SELECTING -> cancelSelectionMode()
        }
    }

    private fun startSelectionMode() {
        updateState(OverlayState.SELECTING)

        rectangleSelectorView = RectangleSelectorView(
            context = this,
            windowManager = windowManager,
            onConfirm = { x, y, w, h ->
                selectedArea = intArrayOf(x, y, w, h)
                consecutiveBlankFrames = 0
                translateManager.resetLastText()

                DebugStore.selectedAreaX.value = x
                DebugStore.selectedAreaY.value = y
                DebugStore.selectedAreaW.value = w
                DebugStore.selectedAreaH.value = h

                rectangleSelectorView?.let(::removeViewSafely)
                rectangleSelectorView = null

                // Overlay uses ORIGINAL portrait coords (same space as the selector).
                setupTranslationOverlay(x, y, w, h)

                updateState(OverlayState.ACTIVE)
                startCaptureLoop()
            },
            onCancel = { cancelSelectionMode() }
        )

        val bounds = displayBounds()
        val horizontalMargin = dpToPx(24)
        val selectorWidth = (bounds.width() * 0.65f).toInt()
            .coerceIn(dpToPx(200), (bounds.width() - horizontalMargin * 2).coerceAtLeast(dpToPx(200)))
        val selectorHeight = (bounds.height() * 0.25f).toInt()
            .coerceIn(dpToPx(100), (bounds.height() - horizontalMargin * 2).coerceAtLeast(dpToPx(100)))
        val rectParams = WindowManager.LayoutParams(
            selectorWidth, selectorHeight,
            getLayoutFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((bounds.width() - selectorWidth) / 2).coerceAtLeast(0)
            y = ((bounds.height() - selectorHeight) / 2).coerceAtLeast(0)
        }
        rectangleSelectorView?.params = rectParams
        rectangleSelectorView?.let { addViewSafely(it, rectParams) }
    }

    private fun cancelSelectionMode() {
        rectangleSelectorView?.let(::removeViewSafely)
        rectangleSelectorView = null
        updateState(if (selectedArea != null) OverlayState.PAUSED else OverlayState.IDLE)
    }

    private fun setupTranslationOverlay(x: Int, y: Int, w: Int, h: Int) {
        translationOverlayView?.let(::removeViewSafely)
        translationOverlayView = TranslationOverlayView(this)

        // Overlay windows are composited in the game's landscape frame, so convert
        // the portrait selection the same way the capture crop is converted.
        val o = toOverlayRect(intArrayOf(x, y, w, h))

        val transParams = WindowManager.LayoutParams(
            o[2],
            WindowManager.LayoutParams.WRAP_CONTENT,
            getLayoutFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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
        translationOverlayView?.let { addViewSafely(it, transParams) }
    }

    private fun startCaptureLoop() {
        if (captureJob?.isActive == true) return

        captureJob = scope.launch(Dispatchers.Default) {
            // Beri waktu user pindah ke game
            delay(2000)

            while (isActive) {
                if (currentState == OverlayState.ACTIVE) {
                    screenCaptureManager.refreshDisplayGeometry()
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
                                    consecutiveBlankFrames = 0
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
                                    consecutiveBlankFrames++
                                    if (consecutiveBlankFrames >= 2) {
                                        DebugStore.translationResult.value = ""
                                        withContext(Dispatchers.Main) { translationOverlayView?.setText("") }
                                    }
                                }
                            } else {
                                DebugStore.captureStatus.value = "FAIL"
                                DebugStore.bitmapCaptured.value = false
                            }
                        } catch (e: CancellationException) {
                            throw e
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

        rectangleSelectorView?.let(::removeViewSafely)
        translationOverlayView?.let(::removeViewSafely)
        controlBarView?.let(::removeViewSafely)
        rectangleSelectorView = null
        translationOverlayView = null
        controlBarView = null

        screenCaptureManager.stop()
        ocrManager.close()
        translateManager.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screenCaptureManager.refreshDisplayGeometry()
        if (currentState == OverlayState.SELECTING) {
            cancelSelectionMode()
        } else {
            selectedArea?.let { setupTranslationOverlay(it[0], it[1], it[2], it[3]) }
        }
    }

    /** Device portrait dimensions as (width, height); system stays portrait. */
    private fun portraitSize(): Pair<Float, Float> {
        val bounds = displayBounds()
        return Pair(
            minOf(bounds.width(), bounds.height()).toFloat(),
            maxOf(bounds.width(), bounds.height()).toFloat(),
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
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, "game_translator_channel")
            .setContentTitle("Game Translator Active")
            .setContentText("Translating screen text...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateState(newState: OverlayState) {
        currentState = newState
        DebugStore.ocrOverlayState.value = newState.name
        controlBarView?.updateState(newState)
    }

    private fun reselectArea() {
        if (currentState == OverlayState.SELECTING) return
        translationOverlayView?.setText("")
        startSelectionMode()
    }

    private fun displayBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also {
                windowManager.defaultDisplay.getRealMetrics(it)
            }
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun addViewSafely(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            DebugStore.logError(e)
            stopSelf()
        }
    }

    private fun removeViewSafely(view: View) {
        try {
            if (view.isAttachedToWindow) windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // The system may already have detached overlay windows during teardown.
        }
    }
}
