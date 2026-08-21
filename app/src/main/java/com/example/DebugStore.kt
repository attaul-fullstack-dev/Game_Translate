package com.example

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.mutableStateOf

object DebugStore {
    @Volatile
    var isActivityVisible = false

    var captureStatus = mutableStateOf("IDLE")
    var bitmapCaptured = mutableStateOf(false)
    var lastBitmap = mutableStateOf<Bitmap?>(null)
    
    var ocrRawText = mutableStateOf("")
    var ocrTextLength = mutableStateOf(0)
    var translationResult = mutableStateOf("")
    
    var serviceState = mutableStateOf("STOPPED")
    var ocrOverlayState = mutableStateOf("IDLE")
    var lastError = mutableStateOf("")
    
    var detectedBlocks = mutableStateOf(0)
    var detectedLines = mutableStateOf(0)
    
    var selectedAreaX = mutableStateOf(0)
    var selectedAreaY = mutableStateOf(0)
    var selectedAreaW = mutableStateOf(0)
    var selectedAreaH = mutableStateOf(0)
    var translationPanelX = mutableStateOf(0)
    var translationPanelY = mutableStateOf(0)
    var translationPanelW = mutableStateOf(0)
    var translationPanelH = mutableStateOf(0)
    var selectedDisplayWidth = mutableStateOf(0)
    var selectedDisplayHeight = mutableStateOf(0)
    var selectedDisplayRotation = mutableStateOf(0)
    var captureFrameWidth = mutableStateOf(0)
    var captureFrameHeight = mutableStateOf(0)

    var enableTranslation = mutableStateOf(true)

    fun resetSession() {
        captureStatus.value = "IDLE"
        bitmapCaptured.value = false
        lastBitmap.value = null
        ocrRawText.value = ""
        ocrTextLength.value = 0
        translationResult.value = ""
        ocrOverlayState.value = "IDLE"
        lastError.value = ""
        detectedBlocks.value = 0
        detectedLines.value = 0
        selectedAreaX.value = 0
        selectedAreaY.value = 0
        selectedAreaW.value = 0
        selectedAreaH.value = 0
        translationPanelX.value = 0
        translationPanelY.value = 0
        translationPanelW.value = 0
        translationPanelH.value = 0
        selectedDisplayWidth.value = 0
        selectedDisplayHeight.value = 0
        selectedDisplayRotation.value = 0
        captureFrameWidth.value = 0
        captureFrameHeight.value = 0
    }

    fun logError(e: Throwable) {
        val errorString = Log.getStackTraceString(e)
        Log.e("TranslationAppError", "Error caught: $errorString")
        lastError.value = errorString
    }

    fun logMessage(message: String) {
        Log.e("TranslationAppError", message)
        lastError.value = message
    }
}
