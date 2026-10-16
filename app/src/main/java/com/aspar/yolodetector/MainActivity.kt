package com.aspar.yolodetector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aspar.yolodetector.automation.GestureAccessibilityService
import com.aspar.yolodetector.capture.ScanBus
import com.aspar.yolodetector.capture.ScanSettings
import com.aspar.yolodetector.capture.ScreenCaptureService
import com.aspar.yolodetector.databinding.ActivityMainBinding
import com.aspar.yolodetector.ml.DetectionResult
import com.aspar.yolodetector.ml.YoloDetector
import com.aspar.yolodetector.ui.OverlayView
import com.aspar.yolodetector.util.FpsCounter
import com.aspar.yolodetector.util.ImageUtils
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * - اسکن صفحه: MediaProjection در [ScreenCaptureService] اجرا می‌شود (مدل خودش را دارد)
 *   و کادرها روی پنجره‌ی شناور کشیده می‌شوند. این Activity فقط مجوزها، شروع/توقف و آمار را مدیریت می‌کند.
 * - عکس گالری: مثل قبل روی mlExecutor همین Activity بنچمارک می‌شود.
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { IDLE, SCREEN, STILL }

    private lateinit var binding: ActivityMainBinding

    /** یک رشته‌ی ثابت برای مدل حالت عکس (الزام GPU Delegate). */
    private val mlExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // فقط روی mlExecutor خوانده/نوشته می‌شوند
    private var detector: YoloDetector? = null
    private val fpsCounter = FpsCounter()
    private var stillBitmap: Bitmap? = null

    @Volatile private var mode = Mode.IDLE

    private val projectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }

    // ---- زنجیره‌ی مجوزها: Overlay → Notification → MediaProjection ----

    private val overlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (!Settings.canDrawOverlays(this)) toast(R.string.overlay_denied)
            requestNotificationThenProjection()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // رد شدنش مانع نیست؛ فقط نوتیفیکیشن سرویس در کشو دیده نمی‌شود
            requestProjection()
        }

    private val screenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                ScanBus.setState(ScanBus.State.STARTING)
                ScreenCaptureService.start(this, res.resultCode, data)
                toast(R.string.scan_started_hint)
            } else {
                toast(R.string.capture_denied)
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) openStillImage(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupControls()
        observeScan()
        // اگر سرویس از قبل در حال اسکن است، مدل دوم روی GPU نسازیم
        if (!ScanBus.isActive) mlExecutor.execute { createDetector() }
    }

    private fun setupControls() = with(binding) {
        confSeek.progress = (ScanSettings.confThreshold * 100).toInt()
        confValue.text = String.format(Locale.US, "%.2f", ScanSettings.confThreshold)
        switchGpu.isChecked = ScanSettings.useGpu
        switchVehicles.isChecked = ScanSettings.vehiclesOnly
        switchAuto.isChecked = ScanSettings.automationEnabled

        confSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                ScanSettings.confThreshold = progress / 100f
                confValue.text = String.format(Locale.US, "%.2f", ScanSettings.confThreshold)
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) {
                if (mode == Mode.STILL) mlExecutor.execute { runStillBenchmark() }
            }
        })

        switchGpu.setOnCheckedChangeListener { _, checked ->
            if (checked == ScanSettings.useGpu) return@setOnCheckedChangeListener
            ScanSettings.useGpu = checked // سرویس در فریم بعدی مدل را بازسازی می‌کند
            if (mode != Mode.SCREEN) {
                statsText.setText(R.string.loading_model)
                mlExecutor.execute { createDetector() }
            }
        }

        switchVehicles.setOnCheckedChangeListener { _, checked ->
            ScanSettings.vehiclesOnly = checked
            if (mode == Mode.STILL) mlExecutor.execute { runStillBenchmark() }
        }

        switchAuto.setOnCheckedChangeListener { sw, checked ->
            if (checked && !GestureAccessibilityService.isEnabled(this@MainActivity)) {
                sw.isChecked = false
                toast(R.string.a11y_enable_hint)
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnCheckedChangeListener
            }
            ScanSettings.automationEnabled = checked
        }

        btnScreen.setOnClickListener {
            if (ScanBus.isActive) ScreenCaptureService.stop(this@MainActivity)
            else startScreenScan()
        }
        btnGallery.setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    // ------------------------------------------------------------------ مدل (حالت عکس)

    /** روی mlExecutor اجرا شود. */
    private fun createDetector() {
        detector?.close()
        detector = null
        fpsCounter.reset()
        try {
            val d = YoloDetector(this, MODEL_FILE, LABELS_FILE, useGpu = ScanSettings.useGpu)
            d.warmUp()
            detector = d
            runOnUiThread {
                if (mode != Mode.SCREEN) binding.statsText.text = String.format(
                    Locale.US, "مدل آماده شد\nشتاب‌دهنده: %s\nورودی: %dx%d | کلاس‌ها: %d",
                    d.delegateName, d.inputWidth, d.inputHeight, d.numClasses
                )
            }
            if (mode == Mode.STILL) runStillBenchmark()
        } catch (t: Throwable) {
            runOnUiThread {
                binding.statsText.text =
                    "خطا در بارگذاری مدل:\n${t.message}\n\nفایل $MODEL_FILE را در app/src/main/assets بگذارید."
            }
        }
    }

    private fun releaseDetector() = mlExecutor.execute {
        detector?.close()
        detector = null
    }

    // ------------------------------------------------------------------ اسکن صفحه

    private fun startScreenScan() {
        if (!Settings.canDrawOverlays(this)) {
            toast(R.string.overlay_needed)
            overlayPermission.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            requestNotificationThenProjection()
        }
    }

    private fun requestNotificationThenProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        // اندروید ۱۴+: کل نمایشگر را اجباری می‌کنیم (نه «یک برنامه») تا مختصات کادرها با صفحه بخواند
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        screenCapture.launch(intent)
    }

    private fun observeScan() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ScanBus.state.collect { state ->
                        val active = state != ScanBus.State.STOPPED
                        binding.btnScreen.setText(if (active) R.string.stop_scan else R.string.start_scan)
                        if (active && mode != Mode.SCREEN) enterScreenMode()
                        else if (!active && mode == Mode.SCREEN) {
                            mode = Mode.IDLE
                            binding.statsText.setText(R.string.scan_stopped)
                        }
                    }
                }
                launch {
                    ScanBus.frame.collect { f ->
                        if (f != null && mode == Mode.SCREEN) {
                            showStats(f.result, f.fps, f.delegate, f.inputWidth, f.inputHeight, null)
                        }
                    }
                }
                launch {
                    ScanBus.message.collect { m ->
                        if (m != null) {
                            binding.statsText.text = m
                            ScanBus.publishMessage(null)
                        }
                    }
                }
            }
        }
    }

    private fun enterScreenMode() {
        mode = Mode.SCREEN
        stillBitmap = null
        binding.imageView.setImageDrawable(null)
        binding.overlay.setResult(null)
        binding.statsText.setText(R.string.scan_running)
        releaseDetector() // GPU را برای مدل سرویس آزاد کن
    }

    // ------------------------------------------------------------------ تصویر ثابت

    private fun openStillImage(uri: Uri) {
        if (ScanBus.isActive) ScreenCaptureService.stop(this)
        mode = Mode.STILL
        binding.overlay.fitMode = OverlayView.FitMode.FIT
        binding.overlay.setResult(null)

        mlExecutor.execute {
            val bmp = try {
                ImageUtils.decode(contentResolver, uri)
            } catch (t: Throwable) {
                runOnUiThread { binding.statsText.text = "خطا در باز کردن تصویر: ${t.message}" }
                return@execute
            }
            stillBitmap = bmp
            runOnUiThread { binding.imageView.setImageBitmap(bmp) }
            if (detector == null) createDetector() // خودش بنچمارک را اجرا می‌کند
            else runStillBenchmark()
        }
    }

    /**
     * روی mlExecutor: یک اجرای گرم‌کننده و سپس [STILL_RUNS] اجرا؛ میانگین زمان‌ها
     * و توان عملیاتی (FPS معادل) گزارش می‌شود.
     */
    private fun runStillBenchmark() {
        val d = detector ?: return
        val bmp = stillBitmap ?: return
        val filter = ScanSettings.classFilter()
        val conf = ScanSettings.confThreshold

        var last: DetectionResult = d.detect(bmp, conf, filter)
        var pre = 0f; var inf = 0f; var post = 0f
        val start = SystemClock.elapsedRealtimeNanos()
        repeat(STILL_RUNS) {
            last = d.detect(bmp, conf, filter)
            pre += last.preprocessMs; inf += last.inferenceMs; post += last.postprocessMs
        }
        val elapsedS = (SystemClock.elapsedRealtimeNanos() - start) / 1e9f
        val avg = last.copy(
            preprocessMs = pre / STILL_RUNS,
            inferenceMs = inf / STILL_RUNS,
            postprocessMs = post / STILL_RUNS,
        )
        val fps = STILL_RUNS / elapsedS
        val delegate = d.delegateName

        runOnUiThread {
            if (mode == Mode.STILL) {
                binding.overlay.setResult(avg)
                showStats(avg, fps, delegate, d.inputWidth, d.inputHeight, STILL_RUNS)
            }
        }
    }

    // ------------------------------------------------------------------ نمایش آمار

    private fun showStats(
        r: DetectionResult, fps: Float, delegate: String, inW: Int, inH: Int, runs: Int?,
    ) {
        val header = if (runs != null) "میانگین $runs اجرا" else "اسکن زنده‌ی صفحه"
        binding.statsText.text = String.format(
            Locale.US,
            "%s\nFPS        : %.1f\nاستنتاج    : %.1f ms\nپیش‌پردازش : %.1f ms\nپس‌پردازش  : %.1f ms\nشتاب‌دهنده : %s\nورودی %dx%d | تصویر %dx%d\nاشیاء      : %d",
            header, fps, r.inferenceMs, r.preprocessMs, r.postprocessMs, delegate,
            inW, inH, r.imageWidth, r.imageHeight, r.detections.size
        )
    }

    override fun onResume() {
        super.onResume()
        // اگر کاربر سرویس را در تنظیمات خاموش کرد، سوئیچ را هم خاموش کن
        if (binding.switchAuto.isChecked && !GestureAccessibilityService.isEnabled(this)) {
            binding.switchAuto.isChecked = false
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        // سرویس اسکن عمداً متوقف نمی‌شود؛ قرار است وقتی کاربر در برنامه‌ی دیگری است کار کند
        mlExecutor.execute {
            detector?.close()
            detector = null
        }
        mlExecutor.shutdown()
    }

    companion object {
        /** خروجی `yolo export model=yolov8n.pt format=tflite` (LiteRT، ورودی NCHW float32) */
        const val MODEL_FILE = "yolov8n.tflite"
        const val LABELS_FILE = "labels.txt"
        private const val STILL_RUNS = 20
    }
}
