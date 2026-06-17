package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

@SuppressLint("ViewConstructor")
class RectangleSelectorView(context: Context, private val windowManager: WindowManager) : View(context) {

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#a855f7")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val fillPaint = Paint().apply {
        color = Color.argb(40, 168, 85, 247)
        style = Paint.Style.FILL
    }
    
    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#a855f7")
        style = Paint.Style.FILL
    }

    var params: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWidth = 0
    private var initialHeight = 0
    
    private var isResizing = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
        
        // Draw resize handle at bottom right
        val cornerSize = 44f
        canvas.drawRect(width.toFloat() - cornerSize, height.toFloat() - cornerSize, width.toFloat(), height.toFloat(), cornerPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val layoutParams = params ?: return true
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialWidth = layoutParams.width
                initialHeight = layoutParams.height
                
                val cornerSize = 88f // Bigger touch target for corner
                isResizing = event.x >= width - cornerSize && event.y >= height - cornerSize
                return true
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
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
