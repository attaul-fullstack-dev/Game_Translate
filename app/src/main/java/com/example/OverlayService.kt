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
    
    private var isPaused = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        screenCaptureManager = ScreenCaptureManager(this)
        ocrManager = OcrManager()
        translateManager = TranslateManager()
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                setupViews()
                startCaptureLoop()
            }
        }
        return START_NOT_STICKY
    }

    private fun setupViews() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 1. Rectangle Selector
        rectangleSelectorView = RectangleSelectorView(this, windowManager)
        val rectParams = WindowManager.LayoutParams(
            800, 300,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        rectangleSelectorView?.params = rectParams
        windowManager.addView(rectangleSelectorView, rectParams)

        // 2. Translation Overlay
        translationOverlayView = TranslationOverlayView(this)
        val transParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 300 // Offset from bottom
        }
        windowManager.addView(translationOverlayView, transParams)

        // 3. Control Bar
        controlBarView = ControlBarView(this, windowManager, 
            onPauseResume = { paused -> isPaused = paused },
            onStop = { stopSelf() }
        )
        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 50
            y = 50
        }
        controlBarView?.params = controlParams
        windowManager.addView(controlBarView, controlParams)
    }

    private fun startCaptureLoop() {
        captureJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (!isPaused) {
                    val rectView = rectangleSelectorView
                    if (rectView != null && rectView.params != null) {
                        try {
                            val location = IntArray(2)
                            withContext(Dispatchers.Main) {
                                rectView.getLocationOnScreen(location)
                            }
                            val x = location[0]
                            val y = location[1]
                            val w = rectView.width
                            val h = rectView.height

                            if (w > 0 && h > 0) {
                                val bitmap = screenCaptureManager.captureRect(x, y, w, h)
                                if (bitmap != null) {
                                    val text = ocrManager.extractText(bitmap)
                                    if (text.isNotBlank()) {
                                        val translated = translateManager.translate(text)
                                        if (translated != null) {
                                            withContext(Dispatchers.Main) {
                                                translationOverlayView?.setText(translated)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        captureJob?.cancel()
        scope.cancel()
        
        rectangleSelectorView?.let { windowManager.removeView(it) }
        translationOverlayView?.let { windowManager.removeView(it) }
        controlBarView?.let { windowManager.removeView(it) }
        
        screenCaptureManager.stop()
        ocrManager.close()
        translateManager.close()
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
