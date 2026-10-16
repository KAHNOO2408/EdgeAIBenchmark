package com.aspar.yolodetector.automation

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.aspar.yolodetector.ml.DetectionResult
import kotlin.math.hypot
import kotlin.math.max

/**
 * پیکربندی اتوماسیون حرکتی. همه‌ی نسبت‌ها 0..1 نسبت به ابعاد صفحه‌اند.
 */
data class AimConfig(
    // ---- انتخاب هدف ----
    /** کلاس‌های هدف؛ پیش‌فرض person در COCO. null = همه */
    val classes: Set<Int>? = setOf(0),
    val minScore: Float = 0.45f,
    val aimPoint: AimPoint = AimPoint.HEAD,
    /** برای HEAD: فاصله‌ی نقطه‌ی هدف از بالای کادر، نسبت به ارتفاع کادر */
    val headRatio: Float = 0.12f,
    val strategy: TargetStrategy = TargetStrategy.CLOSEST_TO_CROSSHAIR,
    /** شعاع قفل روی هدف قبلی (پیکسل صفحه)؛ 0 = خاموش */
    val stickyRadiusPx: Float = 150f,

    // ---- هندسه ----
    /** نقطه‌ی مرجع دید (crosshair)، معمولاً مرکز صفحه */
    val crosshairX: Float = 0.5f,
    val crosshairY: Float = 0.5f,
    /**
     * مبدا کشیدن انگشت. برای اپ/بازی‌ای که کل صفحه دوربین را می‌چرخاند همان مرکز؛
     * اگر ناحیه‌ی کنترل دوربین سمت راست صفحه است، مثلاً 0.75 / 0.5
     */
    val originX: Float = 0.5f,
    val originY: Float = 0.5f,

    // ---- کنترل حرکت ----
    /**
     * ضریب تناسبی: طول کشیدن = gain × فاصله‌ی هدف تا crosshair.
     * با origin = crosshair و gain = 1 دقیقاً «از مرکز تا مختصات هدف» کشیده می‌شود.
     * چون بین ارسال ژست و دیدن نتیجه‌اش چند فریم تأخیر هست، gain < 1 از overshoot جلوگیری می‌کند.
     */
    val gain: Float = 0.6f,
    /** اگر هدف نزدیک‌تر از این به crosshair باشد حرکتی ارسال نمی‌شود (جلوگیری از لرزش) */
    val deadZonePx: Float = 24f,
    /** حداکثر طول یک کشیدن */
    val maxStepPx: Float = 350f,
    /** هموارسازی نمایی مختصات هدف بین فریم‌ها: 1 = بدون هموارسازی */
    val smoothing: Float = 0.5f,

    // ---- زمان‌بندی (Throttling) ----
    /** مدت هر ژست؛ بیشتر = نرم‌تر ولی کندتر */
    val gestureDurationMs: Long = 60L,
    /** حداقل فاصله‌ی شروع دو ژست پشت‌سرهم */
    val intervalMs: Long = 120L,
    /** بعد از گم شدن هدف، این مدت صبر تا هموارسازی ریست شود */
    val lostTargetResetMs: Long = 300L,
)

/** وضعیت آخرین فریم، برای رسم روی Overlay و HUD. */
data class AimState(
    val target: PointF?,
    val crosshair: PointF,
    val dispatched: Boolean,
    val serviceConnected: Boolean,
    val sent: Long,
    val dropped: Long,
)

/**
 * هسته‌ی اتوماسیون: فریم ← انتخاب هدف ← هموارسازی ← dead-zone ← throttle ← ژست.
 *
 * [onFrame] روی رشته‌ی ML صدا زده می‌شود؛ خود dispatchGesture روی Main انجام می‌شود.
 */
class AimController(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    @Volatile var config = AimConfig()
        set(value) {
            field = value
            throttle.intervalMs = max(value.intervalMs, value.gestureDurationMs + 16L)
        }

    private val throttle = GestureThrottle(
        intervalMs = max(config.intervalMs, config.gestureDurationMs + 16L)
    )

    // فقط روی رشته‌ی ML
    private var smoothX = Float.NaN
    private var smoothY = Float.NaN
    private var lastSeenMs = 0L

    fun reset() {
        smoothX = Float.NaN; smoothY = Float.NaN
        throttle.reset()
    }

    fun onFrame(result: DetectionResult, screenW: Int, screenH: Int): AimState {
        val cfg = config
        val now = SystemClock.uptimeMillis()
        val service = GestureAccessibilityService.instance
        val crosshair = PointF(screenW * cfg.crosshairX, screenH * cfg.crosshairY)

        val previous = if (smoothX.isNaN()) null else PointF(smoothX, smoothY)
        val target = TargetMath.selectTarget(
            result, screenW, screenH,
            classes = cfg.classes,
            minScore = cfg.minScore,
            point = cfg.aimPoint,
            headRatio = cfg.headRatio,
            strategy = cfg.strategy,
            crosshair = crosshair,
            previous = previous,
            stickyRadiusPx = cfg.stickyRadiusPx,
        )

        if (target == null) {
            if (now - lastSeenMs > cfg.lostTargetResetMs) {
                smoothX = Float.NaN; smoothY = Float.NaN
            }
            return state(null, crosshair, false, service != null)
        }
        lastSeenMs = now

        // ---- هموارسازی نمایی (EMA) برای کاهش لرزش کادرها ----
        val a = cfg.smoothing.coerceIn(0.05f, 1f)
        if (smoothX.isNaN()) {
            smoothX = target.screenPoint.x; smoothY = target.screenPoint.y
        } else {
            smoothX += a * (target.screenPoint.x - smoothX)
            smoothY += a * (target.screenPoint.y - smoothY)
        }
        val aim = PointF(smoothX, smoothY)

        if (service == null) return state(aim, crosshair, false, false)

        // ---- dead zone ----
        val ex = aim.x - crosshair.x
        val ey = aim.y - crosshair.y
        val err = hypot(ex, ey)
        if (err < cfg.deadZonePx) return state(aim, crosshair, false, true)

        // ---- throttle ----
        if (!throttle.tryAcquire(now)) return state(aim, crosshair, false, true)

        // ---- طول گام: تناسبی + سقف ----
        var dx = ex * cfg.gain
        var dy = ey * cfg.gain
        val len = hypot(dx, dy)
        if (len > cfg.maxStepPx) {
            val k = cfg.maxStepPx / len
            dx *= k; dy *= k
        }

        val maxX = screenW - 1f
        val maxY = screenH - 1f
        val from = PointF(
            (screenW * cfg.originX).coerceIn(0f, maxX),
            (screenH * cfg.originY).coerceIn(0f, maxY)
        )
        val to = PointF((from.x + dx).coerceIn(0f, maxX), (from.y + dy).coerceIn(0f, maxY))

        if (hypot(to.x - from.x, to.y - from.y) < 1f) { // به لبه‌ی صفحه چسبیده
            throttle.release()
            return state(aim, crosshair, false, true)
        }

        mainHandler.post {
            val accepted = service.swipe(from, to, cfg.gestureDurationMs) { throttle.release() }
            if (!accepted) throttle.release()
        }
        return state(aim, crosshair, true, true)
    }

    private fun state(target: PointF?, crosshair: PointF, dispatched: Boolean, connected: Boolean) =
        AimState(target, crosshair, dispatched, connected, throttle.sent, throttle.dropped)
}
