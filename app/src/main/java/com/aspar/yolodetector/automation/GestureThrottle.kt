package com.aspar.yolodetector.automation

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * کنترل نرخ ارسال ژست‌ها:
 *  1. حداقل فاصله‌ی زمانی بین دو ارسال ([intervalMs])
 *  2. فقط یک ژست در جریان (in-flight) — تا ژست قبلی تمام نشده، بعدی ارسال نمی‌شود
 *  3. watchdog: اگر callback سیستم هیچ‌وقت نرسید، بعد از [stuckTimeoutMs] قفل آزاد می‌شود
 *
 * thread-safe؛ معمولاً از رشته‌ی ML صدا زده می‌شود و [release] از Main.
 */
class GestureThrottle(
    @Volatile var intervalMs: Long = 120L,
    @Volatile var stuckTimeoutMs: Long = 1_000L,
) {
    private val inFlight = AtomicBoolean(false)
    private val inFlightSince = AtomicLong(0L)
    private val lastDispatch = AtomicLong(0L)

    /** آمار ساده برای HUD */
    @Volatile var sent = 0L; private set
    @Volatile var dropped = 0L; private set

    /**
     * اگر اجازه‌ی ارسال هست true برمی‌گرداند و حالت in-flight را قفل می‌کند.
     * بعد از پایان ژست (یا رد شدنش) حتماً [release] صدا زده شود.
     */
    fun tryAcquire(now: Long = SystemClock.uptimeMillis()): Boolean {
        if (inFlight.get()) {
            if (now - inFlightSince.get() < stuckTimeoutMs) {
                dropped++
                return false
            }
            inFlight.set(false) // callback گم شده
        }
        if (now - lastDispatch.get() < intervalMs) {
            dropped++
            return false
        }
        if (!inFlight.compareAndSet(false, true)) {
            dropped++
            return false
        }
        inFlightSince.set(now)
        lastDispatch.set(now)
        sent++
        return true
    }

    fun release() {
        inFlight.set(false)
    }

    fun reset() {
        inFlight.set(false)
        lastDispatch.set(0L)
        sent = 0; dropped = 0
    }
}
