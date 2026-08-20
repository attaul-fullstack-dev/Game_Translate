package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

@SuppressLint("ViewConstructor")
class TranslationOverlayView(context: Context) : FrameLayout(context) {

    private val textState = mutableStateOf("")
    private val visibleState = mutableStateOf(false)
    private val lifecycleOwner = MyLifecycleOwner()

    init {
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        
        val composeView = ComposeView(context).apply {
            setContent {
                val text by textState
                val isVisible by visibleState
                
                if (isVisible && text.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xE6000000)) // darker grayish-black
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = text,
                            color = Color.White,
                            fontSize = 14.sp,
                            maxLines = 5
                        )
                    }
                }
            }
        }
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    fun setText(text: String) {
        textState.value = text
        visibleState.value = text.isNotBlank()
    }

    /**
     * Hides the translated text before MediaProjection takes the next frame.
     * Returns true when a newly captured frame is required to avoid OCR feedback.
     */
    fun hideForCapture(): Boolean {
        val needsFreshFrame = visibility == View.VISIBLE && textState.value.isNotBlank()
        if (needsFreshFrame) visibility = View.INVISIBLE
        return needsFreshFrame
    }

    fun setCaptureHidden(hidden: Boolean) {
        visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    override fun onDetachedFromWindow() {
        lifecycleOwner.destroy()
        super.onDetachedFromWindow()
    }
}

class MyLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    fun performRestore(savedState: android.os.Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }

    fun destroy() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
