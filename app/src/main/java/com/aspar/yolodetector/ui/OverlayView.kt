package com.aspar.yolodetector.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.aspar.yolodetector.ml.DetectionResult
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * کادرها را روی تصویر می‌کشد.
 * - FIT برای ImageView با fitCenter (حالت عکس گالری)
 * - FILL برای پیش‌نمایش با fillCenter
 * - SCREEN برای پنجره‌ی شناور روی کل نمایشگر: مختصات تصویرِ گرفته‌شده از صفحه به
 *   مختصات واقعی صفحه ([screenWidth]×[screenHeight]) نگاشت می‌شود و جابه‌جایی خود View
 *   روی صفحه (getLocationOnScreen) کم می‌شود.
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class FitMode { FILL, FIT, SCREEN }

    var fitMode = FitMode.FILL
        set(value) {
            field = value; invalidate()
        }

    /** فقط برای FitMode.SCREEN: اندازه‌ی واقعی نمایشگر در جهت فعلی. */
    var screenWidth = 0
    var screenHeight = 0

    private var result: DetectionResult? = null
    private var hudText: String? = null
    private val screenLoc = IntArray(2)
    private val density = resources.displayMetrics.density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
    }
    private val hudBgPaint = Paint().apply { color = 0x99000000.toInt() }
    private val rect = RectF()
    private val labelRect = RectF()

    private val palette = intArrayOf(
        0xFF00C2A8.toInt(), 0xFFFF6B3D.toInt(), 0xFF4D7CFF.toInt(), 0xFFFFC233.toInt(),
        0xFFE0457B.toInt(), 0xFF7BD148.toInt(), 0xFFA66BFF.toInt(), 0xFF2EC4F2.toInt(),
    )

    fun setResult(r: DetectionResult?, hud: String? = null) {
        result = r
        hudText = hud
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = result ?: return
        if (r.imageWidth == 0 || r.imageHeight == 0) return

        val iw = r.imageWidth.toFloat()
        val ih = r.imageHeight.toFloat()
        var sx = 1f
        var sy = 1f
        var dx = 0f
        var dy = 0f
        when (fitMode) {
            FitMode.FILL, FitMode.FIT -> {
                val s = if (fitMode == FitMode.FILL) max(width / iw, height / ih)
                else min(width / iw, height / ih)
                sx = s; sy = s
                dx = (width - iw * s) / 2f
                dy = (height - ih * s) / 2f
            }
            FitMode.SCREEN -> {
                // فریم = کل صفحه با مقیاس کمتر؛ پس فقط بزرگ‌نمایی و کم کردن مکان View
                val sw = if (screenWidth > 0) screenWidth.toFloat() else width.toFloat()
                val sh = if (screenHeight > 0) screenHeight.toFloat() else height.toFloat()
                getLocationOnScreen(screenLoc)
                sx = sw / iw; sy = sh / ih
                dx = -screenLoc[0].toFloat()
                dy = -screenLoc[1].toFloat()
            }
        }

        val fm = textPaint.fontMetrics
        val textH = fm.descent - fm.ascent
        val pad = 4f * density

        for (d in r.detections) {
            val color = palette[d.classId % palette.size]
            boxPaint.color = color
            labelBgPaint.color = color

            rect.set(
                d.box.left * sx + dx, d.box.top * sy + dy,
                d.box.right * sx + dx, d.box.bottom * sy + dy
            )
            canvas.drawRoundRect(rect, 6f * density, 6f * density, boxPaint)

            val text = "${d.label} ${(d.score * 100).roundToInt()}%"
            val tw = textPaint.measureText(text)
            val top = if (rect.top - textH - 2 * pad >= 0) rect.top - textH - 2 * pad else rect.top
            labelRect.set(rect.left, top, rect.left + tw + 2 * pad, top + textH + 2 * pad)
            canvas.drawRect(labelRect, labelBgPaint)
            canvas.drawText(text, labelRect.left + pad, labelRect.top + pad - fm.ascent, textPaint)
        }

        hudText?.let { hud ->
            // پایین-وسط صفحه تا روی status bar نیفتد
            val tw = textPaint.measureText(hud)
            val left = (width - tw) / 2f - pad
            val top = height - textH - 2 * pad - 48f * density
            labelRect.set(left, top, left + tw + 2 * pad, top + textH + 2 * pad)
            canvas.drawRect(labelRect, hudBgPaint)
            canvas.drawText(hud, left + pad, top + pad - fm.ascent, textPaint)
        }
    }
}
