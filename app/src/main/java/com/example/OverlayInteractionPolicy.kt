package com.example

enum class OverlayState {
    IDLE,
    SELECTING,
    ACTIVE,
    PAUSED,
}

internal enum class BubbleTapAction {
    START_SELECTION,
    PAUSE,
    CANCEL_SELECTION,
}

/** Keeps bubble semantics independent from the icon rendering. */
internal object OverlayInteractionPolicy {
    fun actionFor(state: OverlayState): BubbleTapAction = when (state) {
        OverlayState.IDLE, OverlayState.PAUSED -> BubbleTapAction.START_SELECTION
        OverlayState.ACTIVE -> BubbleTapAction.PAUSE
        OverlayState.SELECTING -> BubbleTapAction.CANCEL_SELECTION
    }
}
