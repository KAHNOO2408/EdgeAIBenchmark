package com.aspar.yolodetector.gesture

import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** یک تکه از حرکت: چند نقطه‌ی پشت سر هم که با سرعت ثابت در durationMs طی می‌شوند */
data class MotionChunk(val points: List<PointF>, val durationMs: Long)

/** کل نقشه‌ی حرکت: تکه‌هایی که با continueStroke به هم زنجیر می‌شوند */
data class MotionPlan(val chunks: List<MotionChunk>, val totalMs: Long)

/**
 * تولید مسیر شبیه دست انسان:
 *  - مسیر: منحنی بزیه‌ی مکعبی با انحنای تصادفی کم (دست هیچ‌وقت خط صاف نمی‌کشد)
 *  - سرعت: پروفایل minimum-jerk (شروع آرام، اوج در وسط، ترمز نرم در انتها)
 *  - زمان: مدل فیتس (حرکت بلندتر = کمی طولانی‌تر، ولی نه خطی)
 *  - لرزش ریز دست (~۱۰ هرتز) که در ابتدا و انتها صفر می‌شود
 *  - گاهی کمی رد شدن از هدف و برگشت (overshoot) برای حرکت‌های بلند
 *
 * نکته‌ی کلیدی: dispatchGesture هر Path را با سرعت ثابت طی می‌کند، پس برای
 * داشتن شتاب و ترمز، مسیر را به فریم‌های هم‌زمان (frameMs) تقسیم می‌کنیم که
 * فاصله‌ی مکانی‌شان متغیر است؛ نتیجه = سرعت متغیر.
 */
object HumanMotion {

    data class Config(
        val frameMs: Long = 16L,            // رزولوشن زمانی (~60fps)
        val framesPerChunk: Int = 3,        // هر چند فریم یک dispatch (کمتر = نرم‌تر، بیشتر = callback کمتر)
        val minDurationMs: Long = 110L,     // حرکت‌های کوتاه از این سریع‌تر نمی‌شوند
        val maxDurationMs: Long = 520L,     // حرکت‌های بلند از این کندتر نمی‌شوند
        val curvature: Float = 0.10f,       // حداکثر انحنا نسبت به طول مسیر
        val jitterPx: Float = 0.8f,         // دامنه‌ی لرزش ریز دست
        val tremorHz: Float = 10f,          // فرکانس لرزش
        val overshootChance: Float = 0.30f, // احتمال رد شدن از هدف در حرکت‌های بلند
        val overshootRatio: Float = 0.04f,  // چقدر رد شود (۴٪ طول مسیر)
        val overshootMinDistPx: Float = 160f
    )

    /** قانون فیتس: T = a + b·log2(1 + D/W) با ±۱۰٪ تغییر تصادفی */
    fun durationFor(distancePx: Float, cfg: Config = Config(), rng: Random = Random.Default): Long {
        val base = 80.0 + 90.0 * (ln(1.0 + distancePx / 40.0) / ln(2.0))
        val variance = 1.0 + (rng.nextDouble() - 0.5) * 0.2
        return (base * variance).toLong().coerceIn(cfg.minDurationMs, cfg.maxDurationMs)
    }

    /** پروفایل minimum-jerk: s(t) = 10t³ − 15t⁴ + 6t⁵ */
    fun minJerk(t: Float): Float = t * t * t * (10f + t * (-15f + 6f * t))

    fun plan(
        start: PointF,
        end: PointF,
        durationMs: Long? = null,
        cfg: Config = Config(),
        rng: Random = Random.Default
    ): MotionPlan {
        val dist = hypot(end.x - start.x, end.y - start.y)
        val total = durationMs ?: durationFor(dist, cfg, rng)

        val overshoot = dist >= cfg.overshootMinDistPx && rng.nextFloat() < cfg.overshootChance
        val pts = ArrayList<PointF>()

        if (overshoot) {
            val past = PointF(
                end.x + (end.x - start.x) * cfg.overshootRatio,
                end.y + (end.y - start.y) * cfg.overshootRatio
            )
            val mainMs = (total * 0.82f).toLong()
            pts += sampleCurve(start, past, mainMs, cfg, rng)
            // برگشت کوتاه و تقریباً صاف به هدف واقعی
            pts += sampleCurve(past, end, total - mainMs, cfg.copy(curvature = 0.02f), rng).drop(1)
        } else {
            pts += sampleCurve(start, end, total, cfg, rng)
        }
        return toChunks(pts, cfg)
    }

    private fun sampleCurve(a: PointF, b: PointF, ms: Long, cfg: Config, rng: Random): List<PointF> {
        val n = max(3, (ms / cfg.frameMs).toInt())
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy).coerceAtLeast(1f)
        val nx = -dy / len // بردار عمود بر مسیر
        val ny = dx / len

        // دو نقطه‌ی کنترل با انحراف هم‌جهت ولی نابرابر → قوس طبیعی و نامتقارن
        val bend1 = (rng.nextFloat() * 2f - 1f) * cfg.curvature * len
        val bend2 = bend1 * (0.3f + rng.nextFloat() * 0.7f)
        val c1 = PointF(a.x + dx * 0.3f + nx * bend1, a.y + dy * 0.3f + ny * bend1)
        val c2 = PointF(a.x + dx * 0.7f + nx * bend2, a.y + dy * 0.7f + ny * bend2)

        val phase = rng.nextFloat() * 2f * PI.toFloat()
        val seconds = ms / 1000f

        return (0..n).map { i ->
            val t = i.toFloat() / n
            val s = minJerk(t)
            val p = cubic(a, c1, c2, b, s)
            // لرزش فقط عمود بر مسیر، و در دو سر مسیر صفر (taper)
            val taper = sin(PI.toFloat() * t)
            val tremor = cfg.jitterPx * taper *
                sin(phase + 2f * PI.toFloat() * cfg.tremorHz * t * seconds)
            PointF(p.x + nx * tremor, p.y + ny * tremor)
        }.also {
            // ابتدا و انتها دقیقاً روی نقاط واقعی باشند
            it.first().set(a)
            it.last().set(b)
        }
    }

    private fun cubic(p0: PointF, p1: PointF, p2: PointF, p3: PointF, t: Float): PointF {
        val u = 1f - t
        val a = u * u * u
        val b = 3f * u * u * t
        val c = 3f * u * t * t
        val d = t * t * t
        return PointF(
            a * p0.x + b * p1.x + c * p2.x + d * p3.x,
            a * p0.y + b * p1.y + c * p2.y + d * p3.y
        )
    }

    /** نقاط را به تکه‌هایی می‌شکند که هر کدام از آخرین نقطه‌ی تکه‌ی قبلی شروع می‌شود */
    private fun toChunks(pts: List<PointF>, cfg: Config): MotionPlan {
        val chunks = ArrayList<MotionChunk>()
        var i = 0
        while (i < pts.size - 1) {
            val j = minOf(i + cfg.framesPerChunk, pts.size - 1)
            chunks += MotionChunk(pts.subList(i, j + 1).map { PointF(it.x, it.y) }, (j - i) * cfg.frameMs)
            i = j
        }
        return MotionPlan(chunks, chunks.sumOf { it.durationMs })
    }
}
