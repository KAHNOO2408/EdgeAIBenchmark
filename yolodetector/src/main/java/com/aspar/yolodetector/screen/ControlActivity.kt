package com.aspar.yolodetector.screen

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.aspar.yolodetector.gesture.SmoothGestureService

/**
 * صفحه‌ی کنترل حداقلی: ۱) فعال‌سازی سرویس دسترس‌پذیری ۲) شروع/توقف اسکن.
 * اگر MainActivity خودتان را دارید، فقط منطق دکمه‌ها را منتقل کنید.
 */
class ControlActivity : Activity() {

    private val reqProjection = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // مدل و برچسب‌ها در app/src/main/assets
        ScreenDetectService.detectorFactory = { ctx ->
            YoloTfliteDetector(ctx, modelAsset = "yolov8n.tflite", scoreThreshold = 0.35f)
        }
        // مدل پیش‌فرض CI (yolov8n روی COCO) کلاس «person» دارد؛ با مدل آموزش‌دیده‌ی خودتان نام کلاس را عوض کنید
        ScreenDetectService.targetLabels = setOf("person", "player")
        ScreenDetectService.minScore = 0.5f

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        root.addView(Button(this).apply {
            text = "فعال‌سازی سرویس حرکت"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "شروع اسکن"
            setOnClickListener {
                if (SmoothGestureService.instance == null) {
                    Toast.makeText(context, "اول سرویس حرکت را فعال کنید", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val mpm = getSystemService(MediaProjectionManager::class.java)
                startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
            }
        })
        root.addView(Button(this).apply {
            text = "توقف"
            setOnClickListener { ScreenDetectService.stop(context) }
        })
        setContentView(root)
    }

    @Deprecated("simple sample")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqProjection && resultCode == RESULT_OK && data != null) {
            ScreenDetectService.start(this, resultCode, data)
            moveTaskToBack(true)
        }
    }
}
