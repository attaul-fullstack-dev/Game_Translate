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
                            val sourceX =
                                maxWidth * region.leftFraction.coerceIn(0f, 1f)
                            val sourceY =
                                maxHeight * region.topFraction.coerceIn(0f, 1f)
                            val sourceWidth =
                                maxWidth * region.widthFraction.coerceIn(0f, 1f)
                            val sourceHeight =
                                maxHeight * region.heightFraction.coerceIn(0f, 1f)
                            val minimumHeight = sourceHeight.coerceAtLeast(28.dp)
                            val x = sourceX.coerceAtMost(
                                (maxWidth - 1.dp).coerceAtLeast(0.dp),
                            )
                            val y = sourceY.coerceAtMost(
                                (maxHeight - minimumHeight).coerceAtLeast(0.dp),
                            )
                            val availableWidth =
                                (maxWidth - x).coerceAtLeast(1.dp)
                            val desiredWidth =
                                (sourceWidth * 1.4f).coerceAtLeast(96.dp)
                            val boxWidth = desiredWidth.coerceAtMost(availableWidth)

                            Box(
                                modifier = Modifier
                                    .offset(x = x, y = y)
                                    .width(boxWidth)
                                    .defaultMinSize(minHeight = minimumHeight)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xE6000000))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = region.text,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    lineHeight = 17.sp,
                                    maxLines = 4,
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

    /**
     * Hides the translated text before MediaProjection takes the next frame.
     * Returns true when a newly captured frame is required to avoid OCR feedback.
     */
    fun hideForCapture(): Boolean {
        val needsFreshFrame =
            visibility == View.VISIBLE && regionsState.value.isNotEmpty()
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
