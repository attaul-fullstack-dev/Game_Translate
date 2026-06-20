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
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var ocrManager: OcrManager
    private lateinit var translateManager: TranslateManager

    private var rectangleSelectorView: RectangleSelectorView? = null
    private var translationOverlayView: TranslationOverlayView? = null
    private var controlBarView: ControlBarView? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var captureJob: Job? = null

    private var currentState = OverlayState.IDLE
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

        val transParams = WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getLayoutFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
       ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        windowManager.addView(translationOverlayView, transParams)
    }

    private fun startCaptureLoop() {
        if (captureJob?.isActive == true) return

        captureJob = scope.launch(Dispatchers.Default) {
            // Beri waktu user pindah ke game
            delay(2000)

            while (isActive) {
                if (currentState == OverlayState.ACTIVE) {
                    val area = selectedArea
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Screen may have rotated: rebuild the VirtualDisplay/ImageReader so captured
        // frames match the new orientation (Masalah 2). The previously selected area
        // no longer maps to the rotated screen, so clear it and drop back to PAUSED
        // until the user re-selects, rather than capturing the wrong region.
        screenCaptureManager.createVirtualDisplay()
        screenCaptureManager.resetBitmap()
        if (selectedArea != null) {
            selectedArea = null
            translationOverlayView?.let { windowManager.removeView(it) }
            translationOverlayView = null
            currentState = OverlayState.IDLE
            controlBarView?.updateState(currentState)
        }
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
