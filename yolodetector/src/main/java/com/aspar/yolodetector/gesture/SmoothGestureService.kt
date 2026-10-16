package com.aspar.yolodetector.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlin.math.hypot

/**
 * سرویس دسترس‌پذیری که حرکت‌های نرم و انسانی را اجرا می‌کند.
 *
 * دو حالت:
 *  1) swipe(from, to)        → یک کشیدن کامل و مستقل (انگشت پایین → حرکت → بالا)
 *  2) steerTowards(target)   → «جوی‌استیک دوربین»: انگشت روی صفحه می‌ماند و
 *                              هر فریم YOLO هدف را به‌روز می‌کند؛ حرکت از جای فعلی
 *                              انگشت ادامه پیدا می‌کند (continueStroke) پس هیچ
 *                              پرش یا برداشتن ناگهانی انگشت رخ نمی‌دهد.
 *
 * همه‌ی متدها thread-safe هستند (همه‌چیز روی main looper اجرا می‌شود).
 */
@RequiresApi(Build.VERSION_CODES.O)
class SmoothGestureService : AccessibilityService() {

    companion object {
        private const val TAG = "SmoothGesture"

        @Volatile
        var instance: SmoothGestureService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    var config = HumanMotion.Config()

    // ---- وضعیت stroke جاری ----
    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var fingerPos: PointF? = null          // انگشت الان کجاست
    private var queue: ArrayDeque<MotionChunk> = ArrayDeque()
    private var dispatching = false
    private var releaseAfterQueue = false
    private var pendingTarget: PointF? = null      // هدف جدیدی که وسط حرکت رسیده
    private var onFinished: (() -> Unit)? = null

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = cancelAll()

    // =====================================================================
    // API عمومی
    // =====================================================================

    /** کشیدن کامل و نرم از from به to؛ انگشت در انتها برداشته می‌شود */
    fun swipe(from: PointF, to: PointF, durationMs: Long? = null, done: (() -> Unit)? = null) =
        main.post {
            cancelAll()
            onFinished = done
            fingerPos = PointF(from.x, from.y)
            enqueue(HumanMotion.plan(from, to, durationMs, config))
            releaseAfterQueue = true
            pump()
        }

    /**
     * چرخاندن دوربین به سمت هدف تشخیص‌داده‌شده.
     * anchor = نقطه‌ی شروع لمس روی ناحیه‌ی دوربین (مثلاً سمت راست صفحه)
     * delta  = چقدر باید کشید (از مختصات YOLO حساب می‌شود، پایین را ببینید)
     *
     * اگر حرکتی در جریان باشد، هدف جدید بعد از تکه‌ی فعلی جایگزین می‌شود
     * (نه این‌که حرکت قطع شود) → بدون رگبار و پرش.
     */
    fun steerTowards(anchor: PointF, delta: PointF) = main.post {
        val from = fingerPos ?: PointF(anchor.x, anchor.y)
        val to = PointF(from.x + delta.x, from.y + delta.y)
        if (hypot(delta.x, delta.y) < 3f) return@post   // dead-zone: حرکت‌های خیلی ریز نادیده

        if (dispatching) {
            pendingTarget = to          // بعد از تمام شدن تکه‌ی فعلی اعمال می‌شود
            return@post
        }
        fingerPos = from
        queue.clear()
        enqueue(HumanMotion.plan(from, to, null, config))
        releaseAfterQueue = false       // انگشت روی صفحه بماند
        pump()
    }

    /** برداشتن انگشت با یک حرکت خیلی کوتاه (نه برداشتن ناگهانی وسط مسیر) */
    fun release() = main.post {
        releaseAfterQueue = true
        if (!dispatching) {
            val p = fingerPos ?: return@post
            queue.clear()
            queue.add(MotionChunk(listOf(p, PointF(p.x + 0.5f, p.y)), config.frameMs))
            pump()
        }
    }

    fun cancelAll() {
        queue.clear()
        pendingTarget = null
        activeStroke = null
        dispatching = false
        fingerPos = null
    }

    // =====================================================================
    // موتور اجرای تکه‌ها
    // =====================================================================

    private fun enqueue(plan: MotionPlan) {
        queue.addAll(plan.chunks)
    }

    private fun pump() {
        if (dispatching) return

        // اگر هدف جدید رسیده، بقیه‌ی صف قبلی را دور بریز و از جای فعلی برنامه‌ریزی کن
        pendingTarget?.let { t ->
            pendingTarget = null
            val from = fingerPos ?: return@let
            queue.clear()
            enqueue(HumanMotion.plan(from, t, null, config))
        }

        val chunk = queue.removeFirstOrNull()
        if (chunk == null) {
            if (releaseAfterQueue) finishStroke()
            return
        }

        val isLast = queue.isEmpty() && pendingTarget == null && releaseAfterQueue
        val path = Path().apply {
            moveTo(chunk.points.first().x, chunk.points.first().y)
            for (i in 1 until chunk.points.size) lineTo(chunk.points[i].x, chunk.points[i].y)
        }
        val duration = chunk.durationMs.coerceAtLeast(1L)

        val stroke = activeStroke?.continueStroke(path, 0, duration, !isLast)
            ?: GestureDescription.StrokeDescription(path, 0, duration, !isLast)

        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatching = true
        activeStroke = stroke

        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                dispatching = false
                fingerPos = chunk.points.last().let { PointF(it.x, it.y) }
                if (isLast) finishStroke() else pump()
            }

            override fun onCancelled(g: GestureDescription?) {
                Log.w(TAG, "gesture cancelled (touch by user or system)")
                cancelAll()
                onFinished?.invoke(); onFinished = null
            }
        }, main)

        if (!ok) {
            Log.e(TAG, "dispatchGesture rejected")
            cancelAll()
        }
    }

    private fun finishStroke() {
        activeStroke = null
        fingerPos = null
        releaseAfterQueue = false
        onFinished?.invoke(); onFinished = null
    }
}
