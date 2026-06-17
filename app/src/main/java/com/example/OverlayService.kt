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
    private var selectedArea: IntArray? = null

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
                setupSelectionMode()
            }
        }
        return START_NOT_STICKY
    }

    private fun setupSelectionMode() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        rectangleSelectorView = RectangleSelectorView(this, windowManager,
            onConfirm = { x, y, w, h ->
                // Switch to active Mode
                selectedArea = intArrayOf(x, y, w, h)
                rectangleSelectorView?.let { windowManager.removeView(it) }
                rectangleSelectorView = null
                
                setupActiveMode(x, y, w, h, layoutFlag)
                startCaptureLoop()
            },
            onCancel = {
                stopSelf()
            }
        )
        
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
    }

    private fun setupActiveMode(x: Int, y: Int, w: Int, h: Int, layoutFlag: Int) {
        // Translation Overlay directly over or near the text
        translationOverlayView = TranslationOverlayView(this)
        val transParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y // overlap text directly
        }
        windowManager.addView(translationOverlayView, transParams)

        // Control Bar (Floating circular button)
        controlBarView = ControlBarView(this, windowManager, 
            onPauseResume = { paused -> 
                isPaused = paused 
                if (paused) {
                    translationOverlayView?.setText("")
                }
            },
            onStop = { stopSelf() }
        )
        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            this.x = 16
        }
        controlBarView?.params = controlParams
        windowManager.addView(controlBarView, controlParams)
    }

    private fun startCaptureLoop() {
        captureJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (!isPaused) {
                    val area = selectedArea
                    if (area != null) {
                        try {
                            val x = area[0]
                            val y = area[1]
                            val w = area[2]
                            val h = area[3]

                            if (w > 0 && h > 0) {
                                val bitmap = screenCaptureManager.captureRect(x, y, w, h)
                                if (bitmap != null) {
                                    val text = ocrManager.extractText(bitmap)
                                    if (text.isNotBlank()) {
                                        val translated = translateManager.translate(text)
                                        if (!translated.isNullOrBlank()) {
                                            withContext(Dispatchers.Main) {
                                                translationOverlayView?.setText(translated)
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) { translationOverlayView?.setText("") }
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) { translationOverlayView?.setText("") }
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
