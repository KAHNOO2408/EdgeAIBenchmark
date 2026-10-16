package com.aspar.yolodetector.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/**
 * سرویس دسترس‌پذیری که فقط برای تزریق ژست لمسی (dispatchGesture) استفاده می‌شود.
 * رویدادهای دسترس‌پذیری را پردازش نمی‌کند.
 *
 * مختصات ورودی همه‌ی متدها پیکسلِ واقعیِ کل نمایشگر (با status/navigation bar) است.
 * همه‌ی متدها روی Main thread صدا زده شوند.
 */
class GestureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /**
     * کشیدن روان از [from] به [to] در [durationMs] میلی‌ثانیه.
     * سرعت در طول مسیر ثابت است؛ نرمی حرکت با مدت زمان و طول گام (در [AimController]) تنظیم می‌شود.
     *
     * @return false اگر سیستم ژست را نپذیرفت (در این حالت [onDone] صدا زده نمی‌شود).
     */
    fun swipe(from: PointF, to: PointF, durationMs: Long, onDone: (completed: Boolean) -> Unit): Boolean {
        val path = Path().apply {
            moveTo(from.x, from.y)
            lineTo(to.x, to.y)
        }
        return dispatchPath(path, durationMs, onDone)
    }

    /**
     * کشیدن روی یک مسیر چندنقطه‌ای (مثلاً منحنی نرم‌شده). نقاط باید حداقل ۲ تا باشند.
     */
    fun drag(points: List<PointF>, durationMs: Long, onDone: (completed: Boolean) -> Unit): Boolean {
        if (points.size < 2) return false
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        return dispatchPath(path, durationMs, onDone)
    }

    private fun dispatchPath(path: Path, durationMs: Long, onDone: (Boolean) -> Unit): Boolean {
        val duration = durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
        return try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
            }, null)
        } catch (t: IllegalArgumentException) {
            // مثلاً مختصات منفی یا مسیر خالی
            Log.w(TAG, "invalid gesture", t)
            false
        }
    }

    companion object {
        private const val TAG = "GestureA11yService"

        /** وقتی کاربر سرویس را در تنظیمات دسترس‌پذیری روشن کرده و سیستم به آن وصل شده است. */
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        /** آیا سرویس در تنظیمات دسترس‌پذیری فعال است؟ */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java)
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any {
                    val si = it.resolveInfo.serviceInfo
                    si.packageName == context.packageName &&
                        si.name == GestureAccessibilityService::class.java.name
                }
        }
    }
}
