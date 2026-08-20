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
import androidx.compose.ui.text.style.TextOverflow
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
internal class TranslationOverlayView(context: Context) : FrameLayout(context) {

    private val regionsState = mutableStateOf<List<OverlayTextRegion>>(emptyList())
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
                val regions by regionsState
                val isVisible by visibleState

                if (isVisible && regions.isNotEmpty()) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        regions.forEach { region ->
                            val bounds = region.displayBounds
                            val x = maxWidth * bounds.left
                            val y = maxHeight * bounds.top
                            val boxWidth = maxWidth * bounds.width
                            val boxHeight = maxHeight * bounds.height

                            Box(
                                modifier = Modifier
                                    .offset(x = x, y = y)
                                    .size(
                                        width = boxWidth,
                                        height = boxHeight,
                                    )
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xE6000000))
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = region.text,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    lineHeight = 16.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun setRegions(regions: List<OverlayTextRegion>) {
        val visibleRegions = regions.filter { it.text.isNotBlank() }
        if (regionsState.value != visibleRegions) {
            regionsState.value = visibleRegions
        }
        visibleState.value = visibleRegions.isNotEmpty()
    }

    fun hideForCapture(): Boolean {
        val wasVisible = visibility == View.VISIBLE && visibleState.value
        if (wasVisible) {
            visibility = View.INVISIBLE
        }
        return wasVisible
    }

    fun setCaptureHidden(hidden: Boolean) {
        visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    override fun onDetachedFromWindow() {
        lifecycleOwner.destroy()
        super.onDetachedFromWindow()
    }
}

internal class MyLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
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
