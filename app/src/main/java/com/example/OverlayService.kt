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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.*

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.aistudio.gametranslator.action.STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_DATA = "DATA"

        private const val TOUCH_THROUGH_ALPHA = 0.79f
        private const val FRAME_WAIT_TIMEOUT_MS = 1_250L
        private const val OCR_TIMEOUT_MS = 5_000L
        private const val TRANSLATION_TIMEOUT_MS = 8_000L
        private const val APP_SWITCH_SETTLE_MS = 500L
    }

    private data class SelectedArea(
        val area: ScreenArea,
        val displayWidth: Int,
        val displayHeight: Int,
        val displayRotation: Int,
        val epoch: Long,
    ) {
        val x: Int get() = area.x
        val y: Int get() = area.y
        val width: Int get() = area.width
        val height: Int get() = area.height

        fun asRect(): IntArray = intArrayOf(x, y, width, height)
    }

    private data class PendingOcrSelection(
        val area: ScreenArea,
        val displayWidth: Int,
        val displayHeight: Int,
        val displayRotation: Int,
    )

    private lateinit var windowManager: WindowManager
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var ocrManager: OcrManager
    private lateinit var translateManager: TranslateManager

    private var rectangleSelectorView: RectangleSelectorView? = null
    private var translationOverlayView: TranslationOverlayView? = null
    private var controlBarView: ControlBarView? = null

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val captureEpoch = CaptureEpoch()
    private val ocrStabilityTracker = OcrStabilityTracker()
    @Volatile
    private var nextCaptureDelayMs = CaptureCadencePolicy.NORMAL_INTERVAL_MS
    private var captureJob: Job? = null
    @Volatile
    private var captureAttemptJob: Job? = null

    @Volatile
    private var currentState = OverlayState.IDLE
    @Volatile
    private var selectedArea: SelectedArea? = null
    private var translationPanelArea: ScreenArea? = null
    private var activityWasVisible = false

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

        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    1,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(1, notification)
            }
        } catch (failure: RuntimeException) {
            DebugStore.serviceState.value = "STOPPED"
            DebugStore.logError(failure)
            stopSelf()
            return START_NOT_STICKY
        }
        DebugStore.serviceState.value = "RUNNING"

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
            startCaptureLoop()
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
            onWindowError = ::handleOverlayWindowError,
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
        when (OverlayInteractionPolicy.actionFor(currentState)) {
            BubbleTapAction.START_SELECTION -> startSelectionMode()
            BubbleTapAction.PAUSE -> {
                invalidateCaptureWork()
                removeTranslationOverlay()
                updateState(OverlayState.PAUSED)
            }
            BubbleTapAction.CANCEL_SELECTION -> cancelSelectionMode()
        }
    }

    private fun startSelectionMode() {
        invalidateCaptureWork()
        rectangleSelectorView?.let(::removeViewSafely)
        rectangleSelectorView = null
        removeTranslationOverlay()
        updateState(OverlayState.SELECTING)

        val bounds = displayBounds()
        val margin = dpToPx(24)
        val availableWidth = (bounds.width() - margin * 2).coerceAtLeast(1)
        val availableHeight = (bounds.height() - margin * 2).coerceAtLeast(1)
        val minimumWidth = dpToPx(200).coerceAtMost(availableWidth)
        val minimumHeight = dpToPx(100).coerceAtMost(availableHeight)
        val selectorWidth = (bounds.width() * 0.75f).toInt()
            .coerceIn(minimumWidth, availableWidth)
        val selectorHeight = (bounds.height() * 0.28f).toInt()
            .coerceIn(minimumHeight, availableHeight)
        val initialArea = ScreenArea(
            x = ((bounds.width() - selectorWidth) / 2).coerceAtLeast(0),
            y = ((bounds.height() - selectorHeight) / 2).coerceAtLeast(0),
            width = selectorWidth,
            height = selectorHeight,
        )
        showAreaSelector(
            title = "1/2  Pilih area teks game (OCR)",
            initialArea = initialArea,
            onConfirm = ::confirmOcrArea,
        )
    }

    private fun confirmOcrArea(area: ScreenArea) {
        val bounds = displayBounds()
        val pending = PendingOcrSelection(
            area = area,
            displayWidth = bounds.width(),
            displayHeight = bounds.height(),
            displayRotation = displayRotation(),
        )
        val panelArea = ScreenAreaSelectionPolicy.defaultTranslationPanel(
            ocrArea = area,
            displayWidth = bounds.width(),
            displayHeight = bounds.height(),
            minimumWidth = dpToPx(240),
            minimumHeight = dpToPx(96),
            margin = dpToPx(16),
        )
        showAreaSelector(
            title = "2/2  Pilih panel hasil terjemahan",
            initialArea = panelArea,
            onConfirm = { confirmedPanel ->
                completeAreaSelection(pending, confirmedPanel)
            },
        )
    }

    private fun completeAreaSelection(
        pending: PendingOcrSelection,
        panelArea: ScreenArea,
    ) {
        val bounds = displayBounds()
        val rotation = displayRotation()
        if (bounds.width() != pending.displayWidth ||
            bounds.height() != pending.displayHeight ||
            rotation != pending.displayRotation
        ) {
            Toast.makeText(
                this,
                "Orientasi berubah. Pilih ulang kedua area.",
                Toast.LENGTH_LONG,
            ).show()
            startSelectionMode()
            return
        }
        if (!ScreenAreaSelectionPolicy.isInsideDisplay(
                area = panelArea,
                displayWidth = bounds.width(),
                displayHeight = bounds.height(),
            ) || ScreenAreaSelectionPolicy.overlaps(pending.area, panelArea)
        ) {
            Toast.makeText(
                this,
                "Panel terjemahan tidak boleh menutupi area OCR.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val epoch = captureEpoch.next()
        val area = SelectedArea(
            area = pending.area,
            displayWidth = pending.displayWidth,
            displayHeight = pending.displayHeight,
            displayRotation = pending.displayRotation,
            epoch = epoch,
        )
        selectedArea = area
        translationPanelArea = panelArea
        ocrStabilityTracker.reset()
        translateManager.resetLastText()
        nextCaptureDelayMs = CaptureCadencePolicy.NORMAL_INTERVAL_MS

        DebugStore.selectedAreaX.value = area.x
        DebugStore.selectedAreaY.value = area.y
        DebugStore.selectedAreaW.value = area.width
        DebugStore.selectedAreaH.value = area.height
        DebugStore.translationPanelX.value = panelArea.x
        DebugStore.translationPanelY.value = panelArea.y
        DebugStore.translationPanelW.value = panelArea.width
        DebugStore.translationPanelH.value = panelArea.height
        DebugStore.selectedDisplayWidth.value = pending.displayWidth
        DebugStore.selectedDisplayHeight.value = pending.displayHeight
        DebugStore.selectedDisplayRotation.value = rotationDegrees(pending.displayRotation)

        rectangleSelectorView?.let(::removeViewSafely)
        rectangleSelectorView = null
        setupTranslationOverlay(panelArea)
        updateState(OverlayState.ACTIVE)
        startCaptureLoop()
    }

    private fun showAreaSelector(
        title: String,
        initialArea: ScreenArea,
        onConfirm: (ScreenArea) -> Unit,
    ) {
        rectangleSelectorView?.let(::removeViewSafely)
        rectangleSelectorView = RectangleSelectorView(
            context = this,
            windowManager = windowManager,
            title = title,
            onConfirm = { x, y, width, height ->
                onConfirm(ScreenArea(x, y, width, height))
            },
            onCancel = ::cancelSelectionMode,
            onWindowError = ::handleOverlayWindowError,
        )
        val rectParams = WindowManager.LayoutParams(
            initialArea.width,
            initialArea.height,
            getLayoutFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialArea.x
            y = initialArea.y
        }
        rectangleSelectorView?.params = rectParams
        rectangleSelectorView?.let { addViewSafely(it, rectParams) }
    }

    private fun cancelSelectionMode() {
        rectangleSelectorView?.let(::removeViewSafely)
        rectangleSelectorView = null
        updateState(
            if (selectedArea != null && translationPanelArea != null) {
                OverlayState.PAUSED
            } else {
                OverlayState.IDLE
            },
        )
    }

    private fun setupTranslationOverlay(area: ScreenArea) {
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
        val safeHeight = area.height.coerceAtLeast(1)
            .coerceAtMost((bounds.height() - safeY).coerceAtLeast(1))

        val transParams = WindowManager.LayoutParams(
            safeWidth,
            safeHeight,
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
            alpha = TOUCH_THROUGH_ALPHA
        }
        translationOverlayView?.let { addViewSafely(it, transParams) }
    }

    private fun startCaptureLoop() {
        if (captureJob?.isActive == true) return

        captureJob = scope.launch(Dispatchers.Default) {
            // Give Android time to put the game back in front.
            delay(CaptureCadencePolicy.INITIAL_SETTLE_MS)

            while (isActive) {
                if (!PermissionHelper.hasOverlayPermission(this@OverlayService)) {
                    withContext(Dispatchers.Main.immediate) {
                        handleOverlayPermissionLost()
                    }
                    break
                }

                if (DebugStore.isActivityVisible) {
                    if (!activityWasVisible) {
                        activityWasVisible = true
                        withContext(Dispatchers.Main.immediate) {
                            invalidateCaptureWork(keepSelection = true)
                            screenCaptureManager.resetBitmap()
                            rectangleSelectorView?.let(::removeViewSafely)
                            rectangleSelectorView = null
                            if (currentState == OverlayState.SELECTING) {
                                updateState(
                                    if (selectedArea != null) OverlayState.PAUSED
                                    else OverlayState.IDLE,
                                )
                            }
                            translationOverlayView?.setCaptureHidden(true)
                        }
                    }
                    DebugStore.captureStatus.value = "PAUSED (APP OPEN)"
                    delay(250)
                    continue
                }

                if (activityWasVisible) {
                    activityWasVisible = false
                    DebugStore.captureStatus.value = "WAITING FOR GAME"
                    delay(APP_SWITCH_SETTLE_MS)
                    if (DebugStore.isActivityVisible) continue
                }

                val selection = selectedArea
                if (currentState == OverlayState.ACTIVE && selection != null) {
                    val attempt = launch {
                        try {
                            captureSelectedArea(selection)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            withContext(Dispatchers.Main.immediate) {
                                if (isCurrentSelection(selection)) {
                                    DebugStore.captureStatus.value = "ERROR"
                                    DebugStore.logError(failure)
                                }
                            }
                        }
                    }
                    captureAttemptJob = attempt
                    try {
                        while (attempt.isActive) {
                            if (DebugStore.isActivityVisible ||
                                currentState != OverlayState.ACTIVE ||
                                selectedArea?.epoch != selection.epoch
                            ) {
                                attempt.cancel()
                            }
                            delay(25)
                        }
                        attempt.join()
                    } finally {
                        if (captureAttemptJob === attempt) {
                            captureAttemptJob = null
                        }
                    }
                    waitForNextCapture(selection.epoch)
                } else {
                    delay(250)
                }
            }
        }
    }

    private suspend fun waitForNextCapture(epoch: Long) {
        val delayMs = nextCaptureDelayMs
        withTimeoutOrNull(delayMs) {
            while (currentState == OverlayState.ACTIVE &&
                selectedArea?.epoch == epoch &&
                !DebugStore.isActivityVisible
            ) {
                delay(50)
            }
        }
    }

    private suspend fun captureSelectedArea(selection: SelectedArea) {
        if (!isCurrentSelection(selection)) return

        var nextOverlayRegions: List<OverlayTextRegion>? = null
        try {
            if (!isCurrentSelection(selection)) return
            val request = screenCaptureManager.requestFreshFrames(count = 1)
            if (request == null) {
                updateIfCurrent(selection) {
                    DebugStore.captureStatus.value = "WAITING FOR FRAME"
                }
                return
            }

            if (!awaitFrameRequest(request, selection, FRAME_WAIT_TIMEOUT_MS)) {
                updateIfCurrent(selection) {
                    DebugStore.captureStatus.value = "WAITING FOR CLEAN FRAME"
                }
                return
            }

            if (!isCurrentSelection(selection)) return
            val (bitmapWidth, bitmapHeight) = screenCaptureManager.bitmapSize() ?: run {
                updateIfCurrent(selection) {
                    DebugStore.captureStatus.value = "WAITING FOR FRAME"
                }
                return
            }
            if (!updateIfCurrent(selection) {
                    DebugStore.captureFrameWidth.value = bitmapWidth
                    DebugStore.captureFrameHeight.value = bitmapHeight
                }
            ) return
            val area = toBitmapRect(selection, bitmapWidth, bitmapHeight)

            if (!updateIfCurrent(selection) {
                    DebugStore.captureStatus.value = "CAPTURING..."
                }
            ) return

            val bitmap = screenCaptureManager.captureRect(area[0], area[1], area[2], area[3])
            if (bitmap == null) {
                updateIfCurrent(selection) {
                    DebugStore.captureStatus.value = "FAIL"
                    DebugStore.bitmapCaptured.value = false
                }
                return
            }

            if (!updateIfCurrent(selection) {
                    DebugStore.captureStatus.value = "OCR..."
                }
            ) return

            val ocr = withTimeoutOrNull(OCR_TIMEOUT_MS) {
                ocrManager.extractText(bitmap)
            }
            if (ocr == null) {
                updateIfCurrent(selection) {
                    DebugStore.captureStatus.value = "OCR TIMEOUT"
                    DebugStore.bitmapCaptured.value = false
                }
                return
            }

            val text = ocr.text
            var regionsToTranslate: List<OcrTextRegion>? = null
            if (!updateIfCurrent(selection) {
                    DebugStore.bitmapCaptured.value = true
                    DebugStore.lastBitmap.value = bitmap
                    DebugStore.ocrRawText.value = text
                    DebugStore.ocrTextLength.value = text.length
                    DebugStore.detectedBlocks.value = ocr.blockCount
                    DebugStore.detectedLines.value = ocr.lineCount

                    val decision = ocrStabilityTracker.observe(ocr.regions)
                    nextCaptureDelayMs = CaptureCadencePolicy.delayAfter(decision)
                    when (decision) {
                        OcrUpdateDecision.WAITING_FOR_STABLE_TEXT -> {
                            DebugStore.captureStatus.value = "STABILIZING OCR..."
                        }
                        OcrUpdateDecision.UNCHANGED -> {
                            DebugStore.captureStatus.value = "UNCHANGED"
                        }
                        OcrUpdateDecision.CLEARED -> {
                            DebugStore.captureStatus.value = "NO TEXT"
                            DebugStore.translationResult.value = ""
                            nextOverlayRegions = emptyList()
                        }
                        OcrUpdateDecision.CHANGED -> {
                            if (DebugStore.enableTranslation.value) {
                                regionsToTranslate = ocr.regions
                                DebugStore.captureStatus.value = "TRANSLATING..."
                            } else {
                                DebugStore.translationResult.value = "SKIPPED (OCR ONLY)"
                                nextOverlayRegions = ocr.regions.map { region ->
                                    OverlayTextRegionMapper.map(
                                        source = region,
                                        translatedText = region.text,
                                        imageWidth = bitmap.width,
                                        imageHeight = bitmap.height,
                                    )
                                }
                                DebugStore.captureStatus.value = "OK"
                            }
                        }
                    }
                }
            ) return

            val stableRegions = regionsToTranslate
            if (stableRegions != null) {
                val translatedRegions = withTimeoutOrNull(TRANSLATION_TIMEOUT_MS) {
                    val mappedRegions = stableRegions.map { region ->
                        val translatedText =
                            translateManager.translate(region.text) ?: region.text
                        OverlayTextRegionMapper.map(
                            source = region,
                            translatedText = translatedText,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
                        )
                    }
                    mappedRegions
                }
                if (translatedRegions == null) {
                    ocrStabilityTracker.reset()
                    nextCaptureDelayMs = CaptureCadencePolicy.STABILITY_RECHECK_MS
                    updateIfCurrent(selection) {
                        DebugStore.captureStatus.value = "TRANSLATION TIMEOUT"
                        DebugStore.translationResult.value = "TIMEOUT"
                    }
                    return
                }
                updateIfCurrent(selection) {
                    DebugStore.translationResult.value =
                        translatedRegions.joinToString(separator = "\n") { it.text }
                    nextOverlayRegions = translatedRegions
                    DebugStore.captureStatus.value = "OK"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ocrStabilityTracker.reset()
            nextCaptureDelayMs = CaptureCadencePolicy.NORMAL_INTERVAL_MS
            withContext(Dispatchers.Main.immediate) {
                if (isCurrentSelection(selection)) {
                    DebugStore.captureStatus.value = "ERROR"
                    DebugStore.logError(e)
                }
            }
        } finally {
            // ML Kit Tasks are not cancellable at the native layer. Do not recycle
            // this bitmap here: a timed-out task may still be reading it.
            restoreTranslationOverlay(selection, nextOverlayRegions)
        }
    }

    private suspend fun awaitFrameRequest(
        request: FrameRequest,
        selection: SelectedArea,
        timeoutMs: Long,
    ): Boolean {
        return try {
            val reachedTarget = withTimeoutOrNull(timeoutMs) {
                while (screenCaptureManager.frameSequence() < request.target) {
                    if (!isCurrentSelection(selection)) {
                        return@withTimeoutOrNull false
                    }
                    delay(16)
                }
                true
            }
            when {
                reachedTarget == true -> true
                !isCurrentSelection(selection) -> false
                else -> screenCaptureManager.frameSequence() > request.baseline
            }
        } finally {
            screenCaptureManager.cancelPendingFrameRequests()
        }
    }

    private suspend fun restoreTranslationOverlay(
        selection: SelectedArea,
        nextRegions: List<OverlayTextRegion>?,
    ) {
        withContext(Dispatchers.Main.immediate) {
            if (!ownsSelection(selection)) return@withContext
            val overlay = translationOverlayView ?: return@withContext
            when {
                DebugStore.isActivityVisible -> overlay.setCaptureHidden(true)
                currentState == OverlayState.ACTIVE -> {
                    nextRegions?.let(overlay::setRegions)
                    overlay.setCaptureHidden(false)
                }
                else -> {
                    overlay.setRegions(emptyList())
                    overlay.setCaptureHidden(false)
                }
            }
        }
    }

    private fun ownsSelection(selection: SelectedArea): Boolean {
        return captureEpoch.matches(selection.epoch) &&
            selectedArea?.epoch == selection.epoch
    }

    private fun isCurrentSelection(selection: SelectedArea): Boolean {
        return ownsSelection(selection) &&
            currentState == OverlayState.ACTIVE &&
            !DebugStore.isActivityVisible
    }

    private suspend fun updateIfCurrent(
        selection: SelectedArea,
        update: () -> Unit,
    ): Boolean {
        return withContext(Dispatchers.Main.immediate) {
            if (!isCurrentSelection(selection)) {
                false
            } else {
                update()
                true
            }
        }
    }

    override fun onDestroy() {
        DebugStore.serviceState.value = "STOPPED"
        captureEpoch.next()
        screenCaptureManager.cancelPendingFrameRequests()
        captureAttemptJob?.cancel()
        captureJob?.cancel()
        scope.cancel()

        rectangleSelectorView?.let(::removeViewSafely)
        translationOverlayView?.let(::removeViewSafely)
        controlBarView?.let(::removeViewSafely)
        rectangleSelectorView = null
        translationOverlayView = null
        controlBarView = null

        try {
            screenCaptureManager.stop()
        } catch (failure: RuntimeException) {
            DebugStore.logError(failure)
        }
        try {
            ocrManager.close()
        } catch (failure: RuntimeException) {
            DebugStore.logError(failure)
        }
        try {
            translateManager.close()
        } catch (failure: RuntimeException) {
            DebugStore.logError(failure)
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (failure: RuntimeException) {
            DebugStore.logError(failure)
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidateCaptureWork()
        screenCaptureManager.refreshDisplayGeometry()
        rectangleSelectorView?.let(::removeViewSafely)
        rectangleSelectorView = null
        removeTranslationOverlay()
        controlBarView?.let(::removeViewSafely)
        controlBarView = null
        setupControlBar()
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
        startSelectionMode()
    }

    private fun invalidateCaptureWork(keepSelection: Boolean = false) {
        val epoch = captureEpoch.next()
        if (keepSelection) {
            selectedArea = selectedArea?.copy(epoch = epoch)
        }
        ocrStabilityTracker.reset()
        translateManager.resetLastText()
        nextCaptureDelayMs = CaptureCadencePolicy.NORMAL_INTERVAL_MS
        captureAttemptJob?.cancel()
        screenCaptureManager.cancelPendingFrameRequests()
    }

    private fun removeTranslationOverlay() {
        translationOverlayView?.let(::removeViewSafely)
        translationOverlayView = null
    }

    private fun handleOverlayPermissionLost() {
        DebugStore.captureStatus.value = "STOPPED"
        DebugStore.logMessage("Izin tampil di atas aplikasi lain telah dicabut.")
        stopSelf()
    }

    private fun handleOverlayWindowError(failure: Throwable) {
        DebugStore.captureStatus.value = "STOPPED"
        DebugStore.logError(failure)
        stopSelf()
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
            handleOverlayWindowError(e)
        }
    }

    private fun removeViewSafely(view: View) {
        try {
            if (view.isAttachedToWindow) windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // The system may already have detached overlay windows during teardown.
        } catch (e: RuntimeException) {
            DebugStore.logError(e)
        }
    }
}
