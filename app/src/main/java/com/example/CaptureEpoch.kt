package com.example

import java.util.concurrent.atomic.AtomicLong

/** Invalidates asynchronous OCR/translation work when the selected area changes. */
internal class CaptureEpoch {
    private val value = AtomicLong(0L)

    fun next(): Long = value.incrementAndGet()

    fun matches(epoch: Long): Boolean = value.get() == epoch
}
