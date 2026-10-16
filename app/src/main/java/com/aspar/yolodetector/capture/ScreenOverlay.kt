package com.aspar.yolodetector.capture

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.aspar.yolodetector.ml.DetectionResult
import com.aspar.yolodetector.ui.OverlayView

/**
 * یک پنجره‌ی شفاف تمام‌صفحه (TYPE_APPLICATION_OVERLAY) روی همه‌ی برنامه‌ها که همان
 * [OverlayView] پروژه را میزبانی می‌کند و کادرها را دقیقاً روی صفحه‌ی واقعی می‌کشد.
 * لمس را نمی‌گیرد؛ کاربر می‌تواند عادی با برنامه‌ی زیرین (مثلاً بازی) کار کند.
 *
 * همه‌ی متدها روی Main thread صدا زده شوند.
 */
class ScreenOverlay(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    val view = OverlayView(context).apply { fitMode = OverlayView.FitMode.SCREEN }
    private var attached = false

    /** اگر مجوز «نمایش روی برنامه‌های دیگر» داده نشده باشد false برمی‌گرداند. */
    fun show(): Boolean {
        if (attached) return true
        if (!Settings.canDrawOverlays(context)) return false

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "YoloScreenOverlay"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0 // زیر status bar / navigation bar هم بکشد
            }
            // اندروید ۱۲+: پنجره‌ی NOT_TOUCHABLE یک برنامه‌ی دیگر اگر کدرتر از این حد باشد،
            // لمس برنامه‌ی زیرین را مسدود می‌کند (untrusted touch occlusion).
            alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(InputManager::class.java)
                    .maximumObscuringOpacityForTouch
            } else 1f
        }

        return try {
            wm.addView(view, lp)
            attached = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "addView overlay failed", t)
            false
        }
    }

    fun setScreenSize(w: Int, h: Int) {
        view.screenWidth = w
        view.screenHeight = h
        view.invalidate()
    }

    fun update(result: DetectionResult?, hud: String?) {
        if (attached) view.setResult(result, hud)
    }

    fun hide() {
        if (!attached) return
        try {
            wm.removeViewImmediate(view)
        } catch (_: Throwable) {
        }
        attached = false
    }

    private companion object {
        const val TAG = "ScreenOverlay"
    }
}
