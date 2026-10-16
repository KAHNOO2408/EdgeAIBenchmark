package com.aspar.yolodetector.util

import android.os.SystemClock

/** FPS سرتاسری (فریم‌های پردازش‌شده در ثانیه) با میانگین متحرک نمایی برای نمایش پایدار. */
class FpsCounter(private val alpha: Float = 0.1f) {
    private var lastNs = 0L
    var fps = 0f
        private set

    fun tick(): Float {
        val now = SystemClock.elapsedRealtimeNanos()
        if (lastNs != 0L) {
            val instant = 1e9f / (now - lastNs).coerceAtLeast(1L)
            fps = if (fps == 0f) instant else fps + alpha * (instant - fps)
        }
        lastNs = now
        return fps
    }

    fun reset() {
        lastNs = 0L
        fps = 0f
    }
}
