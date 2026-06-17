package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

enum class OverlayState {
    IDLE, SELECTING, ACTIVE, PAUSED
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ControlBarView(
    context: Context,
    private val windowManager: WindowManager,
    private val onBubbleTap: () -> Unit
) : FrameLayout(context) {

    private var state = mutableStateOf(OverlayState.IDLE)
    var params: WindowManager.LayoutParams? = null
    
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    fun updateState(newState: OverlayState) {
        state.value = newState
    }

    init {
        val lifecycleOwner = MyLifecycleOwner()
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        val composeView = ComposeView(context).apply {
            setContent {
                val currentState by state
                
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { onBubbleTap() },
                    contentAlignment = Alignment.Center
                ) {
                    val icon = when (currentState) {
                        OverlayState.IDLE, OverlayState.SELECTING -> Icons.Default.Translate
                        OverlayState.ACTIVE -> Icons.Default.Pause
                        OverlayState.PAUSED -> Icons.Default.PlayArrow
                    }
                    val iconTint = when (currentState) {
                        OverlayState.IDLE -> Color(0xFFA855F7)
                        OverlayState.SELECTING -> Color.Gray
                        OverlayState.ACTIVE -> Color.Green
                        OverlayState.PAUSED -> Color.Red
                    }
                    
                    Icon(
                        imageVector = icon,
                        contentDescription = "Overlay Menu",
                        tint = iconTint
                    )
                }
            }
        }
        addView(composeView)
        
        setOnTouchListener { _, event ->
            val layoutParams = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    return@setOnTouchListener false 
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(this, layoutParams)
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }
}

// Reusing MyLifecycleOwner if it isn't defined elsewhere in this file
// (It's actually defined in TranslationOverlayView, but compiling might complain if we redefine it. 
//  Since they are in the same package and it wasn't strictly private, let's just make it private here if needed, 
//  or use the public one from the package. Given it's already in the package, we can just use MyLifecycleOwner() directly without declaring it here).
