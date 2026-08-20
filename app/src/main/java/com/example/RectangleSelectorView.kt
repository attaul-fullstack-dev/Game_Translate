package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class RectangleSelectorView(
    context: Context,
    private val windowManager: WindowManager,
    private val onConfirm: (Int, Int, Int, Int) -> Unit,
    private val onCancel: () -> Unit
) : FrameLayout(context) {

    private val lifecycleOwner = MyLifecycleOwner()
    var params: WindowManager.LayoutParams? = null

    private var isTrackingGesture = false
    private var isResizing = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWidth = 0
    private var initialHeight = 0

    init {
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        val composeView = ComposeView(context).apply {
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {

                    // Dashed border box
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

                    // Action buttons top-end
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

                    // Resize handle bottom-end
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
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Let Compose own taps on the confirm/cancel buttons.
                isTrackingGesture = !isInsideActionButtons(event)
                return isTrackingGesture
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isTrackingGesture = false
        }
        return isTrackingGesture
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val layoutParams = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialWidth = layoutParams.width
                initialHeight = layoutParams.height

                val resizeHandle = dpToPx(48).toFloat()
                isResizing = event.x >= width - resizeHandle && event.y >= height - resizeHandle
                isTrackingGesture = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val bounds = displayBounds()
                if (isResizing) {
                    val maximumWidth = (bounds.width() - initialX).coerceAtLeast(dpToPx(200))
                    val maximumHeight = (bounds.height() - initialY).coerceAtLeast(dpToPx(100))
                    layoutParams.width = (initialWidth + (event.rawX - initialTouchX).toInt())
                        .coerceIn(dpToPx(200), maximumWidth)
                    layoutParams.height = (initialHeight + (event.rawY - initialTouchY).toInt())
                        .coerceIn(dpToPx(100), maximumHeight)
                } else {
                    layoutParams.x = (initialX + (event.rawX - initialTouchX).toInt())
                        .coerceIn(0, (bounds.width() - layoutParams.width).coerceAtLeast(0))
                    layoutParams.y = (initialY + (event.rawY - initialTouchY).toInt())
                        .coerceIn(0, (bounds.height() - layoutParams.height).coerceAtLeast(0))
                }
                windowManager.updateViewLayout(this, layoutParams)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTrackingGesture = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        lifecycleOwner.destroy()
        super.onDetachedFromWindow()
    }

    private fun isInsideActionButtons(event: MotionEvent): Boolean {
        val actionWidth = dpToPx(112)
        val actionHeight = dpToPx(64)
        return event.x >= width - actionWidth && event.y <= actionHeight
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
