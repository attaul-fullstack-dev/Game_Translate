package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.FrameLayout
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ControlBarView(
    context: Context,
    private val windowManager: WindowManager,
    onPauseResume: (Boolean) -> Unit,
    onStop: () -> Unit
) : FrameLayout(context) {

    private var isPaused = mutableStateOf(false)
    var params: WindowManager.LayoutParams? = null
    
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

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
                val paused by isPaused
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1A1A2E))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row {
                        IconButton(onClick = { 
                            isPaused.value = !paused 
                            onPauseResume(isPaused.value)
                        }) {
                            if (paused) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Resume",
                                    tint = Color.White
                                )
                            } else {
                                Icon(
                                    painter = painterResource(android.R.drawable.ic_media_pause),
                                    contentDescription = "Pause",
                                    tint = Color.White
                                )
                            }
                        }
                        IconButton(onClick = { onStop() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Stop",
                                tint = Color.Red
                            )
                        }
                    }
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
                    return@setOnTouchListener false // Let click events pass through
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
