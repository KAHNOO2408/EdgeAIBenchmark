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
    override fun onInterrupt() = resetStroke()

    // =====================================================================
    // حرکت نرم شبیه دست (HumanMotion + continueStroke)
    // =====================================================================

    /** پارامترهای مسیر و سرعت؛ از هر رشته‌ای قابل تغییر */
    @Volatile var motionConfig = HumanMotion.Config()

    // ---- وضعیت stroke جاری (فقط Main) ----
    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var fingerPos: PointF? = null
    private val chunkQueue = ArrayDeque<MotionChunk>()
    private var chunkInFlight = false
    private var liftAfterQueue = false
    private var pendingTarget: PointF? = null
    private var regrip: Triple<PointF, PointF, android.graphics.RectF>? = null   // (origin, delta, bounds) برای گرفتن دوباره بعد از برداشتن انگشت
    private var strokeDone: ((Boolean) -> Unit)? = null

    /** آیا انگشت مجازی الان روی صفحه است؟ */
    val isFingerDown: Boolean get() = activeStroke != null || chunkInFlight

    /**
     * یک کشیدن کامل و مستقل با مسیر بزیه + سرعت minimum-jerk + زمان فیتس.
     * اگر stroke دیگری در جریان باشد false برمی‌گرداند.
     */
    fun humanSwipe(from: PointF, to: PointF, onDone: (completed: Boolean) -> Unit): Boolean {
        if (isFingerDown) return false
        fingerPos = PointF(from.x, from.y)
        strokeDone = onDone
        chunkQueue.addAll(HumanMotion.plan(from, to, null, motionConfig).chunks)
        liftAfterQueue = true
        pump()
        return true
    }

    /**
     * هدایت پیوسته‌ی دید: انگشت روی صفحه می‌ماند و به اندازه‌ی [delta] جابه‌جا می‌شود.
     *  - اگر انگشت بالاست، از [origin] پایین می‌آید.
     *  - اگر وسط حرکت هدف جدید برسد، بعد از تکه‌ی جاری از همان جای انگشت به مقصد جدید می‌رود
     *    (بدون برداشتن انگشت و بدون پرش).
     *  - اگر مقصد از [bounds] بیرون بزند، انگشت نرم برداشته شده و از [origin] دوباره گرفته می‌شود.
     */
    fun steerTowards(origin: PointF, delta: PointF, bounds: android.graphics.RectF) {
        val from = fingerPos ?: PointF(origin.x, origin.y)
        val to = PointF(from.x + delta.x, from.y + delta.y)

        if (!bounds.contains(to.x, to.y)) {
            if (isFingerDown) {
                // اول انگشت را همین‌جا بردار، بعد از origin دوباره شروع کن
                regrip = Triple(PointF(origin.x, origin.y), PointF(delta.x, delta.y), android.graphics.RectF(bounds))
                pendingTarget = null
                chunkQueue.clear()
                liftAfterQueue = true
                if (!chunkInFlight) queueLift()
                pump()
                return
            }
            // انگشت بالاست: از origin شروع کن و مقصد را داخل محدوده نگه دار
            to.x = (origin.x + delta.x).coerceIn(bounds.left, bounds.right)
            to.y = (origin.y + delta.y).coerceIn(bounds.top, bounds.bottom)
            fingerPos = PointF(origin.x, origin.y)
            chunkQueue.addAll(HumanMotion.plan(fingerPos!!, to, null, motionConfig).chunks)
            liftAfterQueue = false
            pump()
            return
        }

        if (chunkInFlight) {
            pendingTarget = to
            liftAfterQueue = false
            return
        }
        fingerPos = from
        chunkQueue.clear()
        chunkQueue.addAll(HumanMotion.plan(from, to, null, motionConfig).chunks)
        liftAfterQueue = false
        pump()
    }

    /** برداشتن نرم انگشت (مثلاً وقتی هدف گم شد یا اتوماسیون خاموش شد). */
    fun releaseFinger() {
        regrip = null
        pendingTarget = null
        if (!isFingerDown) return
        chunkQueue.clear()
        liftAfterQueue = true
        if (!chunkInFlight) {
            queueLift()
            pump()
        }
    }

    private fun queueLift() {
        val p = fingerPos ?: return
        chunkQueue.add(MotionChunk(listOf(PointF(p.x, p.y), PointF(p.x + 0.5f, p.y)), motionConfig.frameMs))
    }

    private fun pump() {
        if (chunkInFlight) return

        pendingTarget?.let { t ->
            pendingTarget = null
            fingerPos?.let { from ->
                chunkQueue.clear()
                chunkQueue.addAll(HumanMotion.plan(from, t, null, motionConfig).chunks)
            }
        }

        val chunk = chunkQueue.removeFirstOrNull()
        if (chunk == null) {
            if (liftAfterQueue && activeStroke != null && fingerPos != null) {
                queueLift(); pump()
            } else if (activeStroke == null || fingerPos == null) {
                resetStroke(); finishStroke(true)
            }
            return
        }

        val isLast = chunkQueue.isEmpty() && pendingTarget == null && liftAfterQueue
        val path = Path().apply {
            moveTo(chunk.points.first().x, chunk.points.first().y)
            for (i in 1 until chunk.points.size) lineTo(chunk.points[i].x, chunk.points[i].y)
        }
        val duration = chunk.durationMs.coerceAtLeast(1L)
        val stroke = try {
            activeStroke?.continueStroke(path, 0L, duration, !isLast)
                ?: GestureDescription.StrokeDescription(path, 0L, duration, !isLast)
        } catch (t: IllegalArgumentException) {
            Log.w(TAG, "invalid stroke", t)
            resetStroke(); finishStroke(false)
            return
        }

        chunkInFlight = true
        activeStroke = stroke
        val ok = try {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        chunkInFlight = false
                        val end = chunk.points.last()
                        fingerPos = PointF(end.x, end.y)
                        if (isLast) {
                            activeStroke = null
                            fingerPos = null
                            finishStroke(true)
                        } else pump()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        // لمس واقعی کاربر یا سیستم stroke را قطع کرد
                        resetStroke(); finishStroke(false)
                    }
                }, null)
        } catch (t: IllegalArgumentException) {
            Log.w(TAG, "dispatch rejected", t); false
        }
        if (!ok) { resetStroke(); finishStroke(false) }
    }

    private fun finishStroke(completed: Boolean) {
        strokeDone?.let { strokeDone = null; it(completed) }
        regrip?.let { (origin, delta, bounds) ->
            regrip = null
            steerTowards(origin, delta, bounds)
        }
    }

    private fun resetStroke() {
        chunkQueue.clear()
        pendingTarget = null
        activeStroke = null
        chunkInFlight = false
        fingerPos = null
        liftAfterQueue = false
    }

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
