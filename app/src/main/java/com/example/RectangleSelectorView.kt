package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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

@SuppressLint("ViewConstructor")
class RectangleSelectorView(
    context: Context,
    private val windowManager: WindowManager,
    private val onConfirm: (Int, Int, Int, Int) -> Unit,
    private val onCancel: () -> Unit
) : FrameLayout(context) {

    var params: WindowManager.LayoutParams? = null
    private var isResizing = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWidth = 0
    private var initialHeight = 0

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
                Box(modifier = Modifier.fillMaxSize()) {
                    // Action Buttons (inside the box)
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1A1A2E)),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(onClick = {
                           val p = params ?: return@IconButton
                           val location = IntArray(2)
                           this@RectangleSelectorView.getLocationOnScreen(location)
                           onConfirm(location[0], location[1], p.width, p.height)
                        }) {
                            Icon(Icons.Default.Check, "Confirm", tint = Color.Green)
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, "Cancel", tint = Color.Red)
                        }
                    }

                    // The Dashed Box
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = Stroke(
                            width = 6f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 20f), 0f)
                        )
                        drawRoundRect(
                            color = Color(0xFFA855F7),
                            style = stroke,
                            cornerRadius = CornerRadius(16f, 16f)
                        )
                        drawRoundRect(
                            color = Color(0x28A855F7),
                            cornerRadius = CornerRadius(16f, 16f)
                        )
                    }

                    // Resize handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(32.dp)
                            .background(
                                color = Color(0xFFA855F7),
                                shape = RoundedCornerShape(topStart = 16.dp, bottomEnd = 16.dp)
                            )
                    )
                }
            }
        }

        addView(composeView)

        // Same touch handling logic
        setOnTouchListener { _, event ->
            val layoutParams = params ?: return@setOnTouchListener true
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialWidth = layoutParams.width
                    initialHeight = layoutParams.height

                    val cornerSizePixels = 88f
                    isResizing = event.x >= layoutParams.width - cornerSizePixels && event.y >= layoutParams.height - cornerSizePixels
                    return@setOnTouchListener false // let compose handle clicks on buttons
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isResizing) {
                        val newWidth = initialWidth + (event.rawX - initialTouchX).toInt()
                        val newHeight = initialHeight + (event.rawY - initialTouchY).toInt()
                        layoutParams.width = maxOf(200, newWidth)
                        layoutParams.height = maxOf(100, newHeight)
                    } else {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    }
                    windowManager.updateViewLayout(this, layoutParams)
                    return@setOnTouchListener true
                }
            }
            false
        }
    }
}
