package com.aspar.yolodetector.automation

import android.graphics.PointF
import android.graphics.RectF
import com.aspar.yolodetector.ml.Detection
import com.aspar.yolodetector.ml.DetectionResult

/** کدام نقطه از کادر هدف است. */
enum class AimPoint {
    /** مرکز هندسی کادر */
    CENTER,

    /** نقطه‌ی بالایی کادر (تخمین سر) — فاصله از لبه‌ی بالا = headRatio × ارتفاع کادر */
    HEAD,
}

/** اگر چند شیء پیدا شد کدام انتخاب شود. */
enum class TargetStrategy { CLOSEST_TO_CROSSHAIR, HIGHEST_SCORE, LARGEST }

/** هدف انتخاب‌شده با مختصات واقعی صفحه. */
data class Target(val detection: Detection, val screenPoint: PointF)

object TargetMath {

    /**
     * نقطه‌ی هدف در فضای پیکسلیِ تصویرِ ورودی (همان فضایی که YoloDetector کادر را برمی‌گرداند).
     */
    fun aimPointInImage(box: RectF, point: AimPoint, headRatio: Float): PointF = when (point) {
        AimPoint.CENTER -> PointF(box.centerX(), box.centerY())
        AimPoint.HEAD -> PointF(
            box.centerX(),
            box.top + box.height() * headRatio.coerceIn(0f, 1f)
        )
    }

    /**
     * تبدیل از فضای فریمِ گرفته‌شده (مثلاً 960×432) به پیکسل واقعی صفحه (مثلاً 2340×1080).
     * فریم همان کل نمایشگر با مقیاس کوچک‌تر است، پس فقط ضرب در نسبت دو محور لازم است.
     */
    fun imageToScreen(p: PointF, imageW: Int, imageH: Int, screenW: Int, screenH: Int): PointF {
        val sx = screenW / imageW.toFloat()
        val sy = screenH / imageH.toFloat()
        return PointF(p.x * sx, p.y * sy)
    }

    /**
     * از بین اشیاء شناسایی‌شده یکی را انتخاب می‌کند و مختصات صفحه‌ی آن را برمی‌گرداند.
     *
     * @param classes فقط این کلاس‌ها (null = همه)
     * @param crosshair نقطه‌ی مرجع روی صفحه (معمولاً مرکز)، برای استراتژی نزدیک‌ترین
     * @param previous هدف فریم قبل؛ اگر کادری نزدیکش باشد ترجیح داده می‌شود تا هدف بین دو شیء نپرد
     */
    fun selectTarget(
        result: DetectionResult,
        screenW: Int,
        screenH: Int,
        classes: Set<Int>?,
        minScore: Float,
        point: AimPoint,
        headRatio: Float,
        strategy: TargetStrategy,
        crosshair: PointF,
        previous: PointF? = null,
        stickyRadiusPx: Float = 0f,
    ): Target? {
        if (result.imageWidth == 0 || result.imageHeight == 0) return null

        val candidates = result.detections
            .filter { it.score >= minScore && (classes == null || it.classId in classes) }
            .map { d ->
                Target(
                    d,
                    imageToScreen(
                        aimPointInImage(d.box, point, headRatio),
                        result.imageWidth, result.imageHeight, screenW, screenH
                    )
                )
            }
        if (candidates.isEmpty()) return null

        // ۱) قفل روی هدف قبلی (چسبندگی) تا هدف بین دو شیء نپرد
        if (previous != null && stickyRadiusPx > 0f) {
            val r2 = stickyRadiusPx * stickyRadiusPx
            candidates
                .filter { dist2(it.screenPoint, previous) <= r2 }
                .minByOrNull { dist2(it.screenPoint, previous) }
                ?.let { return it }
        }

        // ۲) انتخاب بر اساس استراتژی
        return when (strategy) {
            TargetStrategy.CLOSEST_TO_CROSSHAIR -> candidates.minByOrNull { dist2(it.screenPoint, crosshair) }
            TargetStrategy.HIGHEST_SCORE -> candidates.maxByOrNull { it.detection.score }
            TargetStrategy.LARGEST -> candidates.maxByOrNull { it.detection.box.width() * it.detection.box.height() }
        }
    }

    private fun dist2(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
