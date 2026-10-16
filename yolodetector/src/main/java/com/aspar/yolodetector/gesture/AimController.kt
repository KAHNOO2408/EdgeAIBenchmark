package com.aspar.yolodetector.gesture

import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot

/**
 * پل بین خروجی YOLO و حرکت دوربین.
 *
 * هر فریم تشخیص را به onDetections بدهید؛ این کلاس:
 *  - هدف نزدیک‌تر به مرکز صفحه را انتخاب می‌کند (و به آن «می‌چسبد» تا بین دو هدف پرش نکند)
 *  - مرکز هدف را با EMA صاف می‌کند تا لرزش باکس‌های YOLO به دوربین منتقل نشود
 *  - فاصله تا مرکز را با یک بهره‌ی کمتر از ۱ به delta تبدیل می‌کند (هر بار بخشی از راه)
 *  - بین دستورها حداقل فاصله‌ی زمانی می‌گذارد → بدون حرکت رگباری
 */
class AimController(
    private val screenW: Int,
    private val screenH: Int,
    /** نقطه‌ی لمس ناحیه‌ی دوربین بازی (معمولاً نیمه‌ی راست صفحه) */
    private val cameraAnchor: PointF = PointF(screenW * 0.72f, screenH * 0.5f),
    /** پیکسل کشیدن به ازای هر پیکسل فاصله؛ با حساسیت دوربین بازی کالیبره کنید */
    var sensitivity: Float = 0.55f,
    /** حداقل فاصله‌ی زمانی بین دو دستور حرکت */
    var minIntervalMs: Long = 90L,
    /** اگر هدف از این فاصله به مرکز نزدیک‌تر باشد کاری نکن */
    var deadZonePx: Float = 24f,
    /** سقف طول یک حرکت تا چرخش‌های تند غیرطبیعی نشوند */
    var maxStepPx: Float = 260f,
    /** ضریب صاف‌سازی EMA (۰ = خیلی صاف/کند، ۱ = بدون صاف‌سازی) */
    var smoothing: Float = 0.35f,
    /** چند فریم بی‌هدف تا انگشت برداشته شود */
    var lostFramesToRelease: Int = 8
) {
    private var smoothed: PointF? = null
    private var lastCmdAt = 0L
    private var lostFrames = 0
    private val center = PointF(screenW / 2f, screenH / 2f)

    /** boxes باید در مختصات صفحه‌ی واقعی باشند (نه ورودی ۶۴۰×۶۴۰ مدل) */
    fun onDetections(boxes: List<RectF>) {
        val svc = SmoothGestureService.instance ?: return

        val target = pickTarget(boxes)
        if (target == null) {
            if (++lostFrames >= lostFramesToRelease) {
                smoothed = null
                svc.release()
            }
            return
        }
        lostFrames = 0

        val s = smoothed?.let {
            PointF(it.x + (target.x - it.x) * smoothing, it.y + (target.y - it.y) * smoothing)
        } ?: target
        smoothed = s

        val dx = s.x - center.x
        val dy = s.y - center.y
        if (hypot(dx, dy) < deadZonePx) return

        val now = SystemClock.uptimeMillis()
        if (now - lastCmdAt < minIntervalMs) return
        lastCmdAt = now

        var mx = dx * sensitivity
        var my = dy * sensitivity
        val len = hypot(mx, my)
        if (len > maxStepPx) { mx *= maxStepPx / len; my *= maxStepPx / len }

        svc.steerTowards(cameraAnchor, PointF(mx, my))
    }

    private fun pickTarget(boxes: List<RectF>): PointF? {
        if (boxes.isEmpty()) return null
        val prev = smoothed
        // اگر هدف قبلی هنوز هست، همان را نگه دار (hysteresis)
        if (prev != null) {
            boxes.minByOrNull { hypot(it.centerX() - prev.x, it.centerY() - prev.y) }
                ?.takeIf { abs(it.centerX() - prev.x) < it.width() * 1.5f }
                ?.let { return PointF(it.centerX(), it.centerY()) }
        }
        return boxes.minByOrNull { hypot(it.centerX() - center.x, it.centerY() - center.y) }
            ?.let { PointF(it.centerX(), it.centerY()) }
    }
}
