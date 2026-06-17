package com.example

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.mutableStateOf

object DebugStore {
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

    var enableTranslation = mutableStateOf(true)

    fun logError(e: Throwable) {
        val errorString = Log.getStackTraceString(e)
        Log.e("TranslationAppError", "Error caught: $errorString")
        lastError.value = errorString
    }
}
