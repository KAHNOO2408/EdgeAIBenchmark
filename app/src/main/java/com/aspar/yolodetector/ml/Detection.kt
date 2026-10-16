package com.aspar.yolodetector.ml

import android.graphics.RectF

/** یک شیء تشخیص داده‌شده؛ مختصات box در فضای پیکسلیِ تصویر اصلی است. */
data class Detection(
    val box: RectF,
    val classId: Int,
    val label: String,
    val score: Float,
)

/** خروجی یک بار اجرای کامل (پیش‌پردازش + استنتاج + پس‌پردازش). */
data class DetectionResult(
    val detections: List<Detection>,
    val imageWidth: Int,
    val imageHeight: Int,
    val preprocessMs: Float,
    val inferenceMs: Float,
    val postprocessMs: Float,
) {
    val totalMs: Float get() = preprocessMs + inferenceMs + postprocessMs
}

object CocoClasses {
    /** bicycle, car, motorcycle, bus, train, truck */
    val VEHICLES: Set<Int> = setOf(1, 2, 3, 5, 6, 7)
}
