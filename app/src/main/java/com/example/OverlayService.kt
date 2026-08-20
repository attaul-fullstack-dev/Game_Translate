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
import android.view.Surface
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
    }

    private data class SelectedArea(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val displayRotation: Int,
    ) {
        fun asRect(): IntArray = intArrayOf(x, y, width, height)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var ocrManager: OcrManager
    private lateinit var translateManager: TranslateManager

    private var rectangleSelectorView: RectangleSelectorView? = null
    private var translationOverlayView: TranslationOverlayView? = null
    private var controlBarView: ControlBarView? = null

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var captureJob: Job? = null

    @Volatile
    private var currentState = OverlayState.IDLE
    @Volatile
    private var selectedArea: SelectedArea? = null
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
            OverlayState.PAUSED -> startSelectionMode()
            OverlayState.SELECTING -> cancelSelectionMode()
        }
    }

    private fun startSelectionMode() {
        translationOverlayView?.setText("")
        translationOverlayView?.setCaptureHidden(false)
        updateState(OverlayState.SELECTING)

        rectangleSelectorView = RectangleSelectorView(
            context = this,
            windowManager = windowManager,
            onConfirm = { x, y, w, h ->
                val bounds = displayBounds()
                val rotation = displayRotation()
                val area = SelectedArea(
                    x = x,
                    y = y,
                    width = w,
                    height = h,
                    displayWidth = bounds.width(),
                    displayHeight = bounds.height(),
                    displayRotation = rotation,
                )
                selectedArea = area
                consecutiveBlankFrames = 0
                translateManager.resetLastText()

                DebugStore.selectedAreaX.value = x
                DebugStore.selectedAreaY.value = y
                DebugStore.selectedAreaW.value = w
                DebugStore.selectedAreaH.value = h
                DebugStore.selectedDisplayWidth.value = bounds.width()
                DebugStore.selectedDisplayHeight.value = bounds.height()
                DebugStore.selectedDisplayRotation.value = rotationDegrees(rotation)

                rectangleSelectorView?.let(::removeViewSafely)
                rectangleSelectorView = null

                setupTranslationOverlay(area)

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

    private fun setupTranslationOverlay(area: SelectedArea) {
        translationOverlayView?.let(::removeViewSafely)
        translationOverlayView = TranslationOverlayView(this)

        // The selector and translation window are both WindowManager overlays, so
        // their coordinates already share the same space. Only the bitmap crop may
        // need a rotation transform.
        val bounds = displayBounds()
        val safeX = area.x.coerceIn(0, (bounds.width() - 1).coerceAtLeast(0))
        val safeY = area.y.coerceIn(0, (bounds.height() - 1).coerceAtLeast(0))
        val safeWidth = area.width.coerceAtLeast(1)
            .coerceAtMost((bounds.width() - safeX).coerceAtLeast(1))

        val transParams = WindowManager.LayoutParams(
            safeWidth,
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
            this.x = safeX
            this.y = safeY
        }
        translationOverlayView?.let { addViewSafely(it, transParams) }
    }

    private fun startCaptureLoop() {
        if (captureJob?.isActive == true) return

        captureJob = scope.launch(Dispatchers.Default) {
            // Beri waktu user pindah ke game
            delay(2000)

            while (isActive) {
                if (DebugStore.isActivityVisible) {
                    DebugStore.captureStatus.value = "PAUSED (APP OPEN)"
                    withContext(Dispatchers.Main) {
                        translationOverlayView?.setCaptureHidden(true)
                    }
                    delay(250)
                    continue
                }

                val selection = selectedArea
                if (currentState == OverlayState.ACTIVE && selection != null) {
                    captureSelectedArea(selection)
                } else {
                    withContext(Dispatchers.Main) {
                        translationOverlayView?.setText("")
                        translationOverlayView?.setCaptureHidden(false)
                    }
                }
                delay(1500)
            }
        }
    }

    private suspend fun captureSelectedArea(selection: SelectedArea) {
        val (bitmapWidth, bitmapHeight) = screenCaptureManager.bitmapSize() ?: run {
            DebugStore.captureStatus.value = "WAITING FOR FRAME"
            return
        }
        DebugStore.captureFrameWidth.value = bitmapWidth
        DebugStore.captureFrameHeight.value = bitmapHeight

        val area = toBitmapRect(selection, bitmapWidth, bitmapHeight)
        val cleanFrameBaseline = withContext(Dispatchers.Main) {
            if (translationOverlayView?.hideForCapture() == true) {
                screenCaptureManager.frameSequence()
            } else {
                null
            }
        }

        if (cleanFrameBaseline != null && !awaitFrameAfter(cleanFrameBaseline)) {
            DebugStore.captureStatus.value = "WAITING FOR CLEAN FRAME"
            restoreTranslationOverlay(null)
            return
        }

        var nextOverlayText: String? = null
        try {
            DebugStore.captureStatus.value = "CAPTURING..."
            val bitmap = screenCaptureManager.captureRect(area[0], area[1], area[2], area[3])
            if (bitmap == null) {
                DebugStore.captureStatus.value = "FAIL"
                DebugStore.bitmapCaptured.value = false
                return
            }

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
                    nextOverlayText = translated.orEmpty()
                } else {
                    DebugStore.translationResult.value = "SKIPPED (OCR ONLY)"
                    nextOverlayText = text
                }
            } else {
                consecutiveBlankFrames++
                if (consecutiveBlankFrames >= 2) {
                    DebugStore.translationResult.value = ""
                    nextOverlayText = ""
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugStore.logError(e)
        } finally {
            restoreTranslationOverlay(nextOverlayText)
        }
    }

    private suspend fun awaitFrameAfter(sequence: Long): Boolean {
        return withTimeoutOrNull(750L) {
            while (screenCaptureManager.frameSequence() <= sequence) {
                if (currentState != OverlayState.ACTIVE || DebugStore.isActivityVisible) {
                    return@withTimeoutOrNull false
                }
                delay(16)
            }
            true
        } ?: false
    }

    private suspend fun restoreTranslationOverlay(nextText: String?) {
        withContext(Dispatchers.Main) {
            val overlay = translationOverlayView ?: return@withContext
            when {
                DebugStore.isActivityVisible -> overlay.setCaptureHidden(true)
                currentState == OverlayState.ACTIVE -> {
                    nextText?.let(overlay::setText)
                    overlay.setCaptureHidden(false)
                }
                else -> {
                    overlay.setText("")
                    overlay.setCaptureHidden(false)
                }
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
        rectangleSelectorView?.let(::removeViewSafely)
        rectangleSelectorView = null
        translationOverlayView?.setText("")
        translationOverlayView?.setCaptureHidden(DebugStore.isActivityVisible)
        updateState(if (selectedArea != null) OverlayState.PAUSED else OverlayState.IDLE)
    }

    private fun toBitmapRect(
        selection: SelectedArea,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): IntArray {
        val quarterTurn = when (selection.displayRotation) {
            Surface.ROTATION_90 -> QuarterTurn.CLOCKWISE
            Surface.ROTATION_270 -> QuarterTurn.COUNTER_CLOCKWISE
            else -> null
        }
        return ScreenRegionMapper.map(
            rect = selection.asRect(),
            sourceWidth = selection.displayWidth,
            sourceHeight = selection.displayHeight,
            targetWidth = bitmapWidth,
            targetHeight = bitmapHeight,
            quarterTurn = quarterTurn,
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
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also {
                windowManager.defaultDisplay.getRealMetrics(it)
            }
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = windowManager.defaultDisplay.rotation

    private fun rotationDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
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
