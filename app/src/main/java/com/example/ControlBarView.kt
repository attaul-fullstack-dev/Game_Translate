package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
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
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ControlBarView(
    context: Context,
    private val windowManager: WindowManager,
    private val onBubbleTap: () -> Unit,
    private val onBubbleLongPress: () -> Unit,
    private val onWindowError: (Throwable) -> Unit,
) : FrameLayout(context) {

    private val state = mutableStateOf(OverlayState.IDLE)
    private val lifecycleOwner = MyLifecycleOwner()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout()
    var params: WindowManager.LayoutParams? = null
    
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var windowUpdateFailed = false

    fun updateState(newState: OverlayState) {
        state.value = newState
    }

    init {
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
                        .background(Color.White),
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

        composeView.setOnTouchListener { _, event ->
            val layoutParams = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val bounds = displayBounds()
                        val bubbleWidth = width.takeIf { it > 0 } ?: dpToPx(48)
                        val bubbleHeight = height.takeIf { it > 0 } ?: dpToPx(48)
                        layoutParams.x = (initialX + dx.toInt())
                            .coerceIn(0, (bounds.width() - bubbleWidth).coerceAtLeast(0))
                        layoutParams.y = (initialY + dy.toInt())
                            .coerceIn(0, (bounds.height() - bubbleHeight).coerceAtLeast(0))
                        updateWindowLayout(layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (event.eventTime - event.downTime >= longPressTimeout) {
                            onBubbleLongPress()
                        } else {
                            performClick()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        onBubbleTap()
        return true
    }

    override fun onDetachedFromWindow() {
        lifecycleOwner.destroy()
        super.onDetachedFromWindow()
    }

    private fun updateWindowLayout(layoutParams: WindowManager.LayoutParams) {
        if (windowUpdateFailed || !isAttachedToWindow) return
        try {
            windowManager.updateViewLayout(this, layoutParams)
        } catch (failure: RuntimeException) {
            windowUpdateFailed = true
            onWindowError(failure)
        }
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

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
