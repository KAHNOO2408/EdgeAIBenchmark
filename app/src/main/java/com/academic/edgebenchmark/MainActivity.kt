package com.academic.edgebenchmark

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    /**
     * مورد ۴: یک ترد ثابت برای «همه‌ی» کارهای مدل: ساخت مفسر، اجرای فریم‌ها، تعویض سخت‌افزار و بستن.
     * GPU Delegate فقط روی همان تردی که ساخته شده اجرا می‌شود.
     */
    private lateinit var inferenceExecutor: ExecutorService

    private lateinit var txtHardware: TextView
    private lateinit var txtPrediction: TextView
    private lateinit var txtFps: TextView
    private lateinit var txtInference: TextView
    private lateinit var txtSystem: TextView
    private lateinit var txtStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var groupAccelerator: RadioGroup

    // متغیرهای زیر فقط روی inferenceExecutor خوانده/نوشته می‌شوند
    private var engine: ImageClassifierEngine? = null
    private val inferenceStats = BenchmarkStats(warmupFrames = WARMUP_FRAMES)
    private val endToEndStats = BenchmarkStats(warmupFrames = WARMUP_FRAMES)
    private val recorder = CsvRecorder()
    private lateinit var monitor: SystemMonitor
    private var recording = false
    private var fpsFrames = 0
    private var fpsWindowStart = SystemClock.elapsedRealtime()
    private var currentFps = 0.0
    private var lastUiUpdate = 0L

    // پرچم روی ترد UI: جلوگیری از اجرای دوباره‌ی listener وقتی کد خودش رادیو را عوض می‌کند
    private var suppressRadioEvents = false

    /** مورد ۶: نتیجه‌ی درخواست مجوز دوربین واقعاً مدیریت می‌شود */
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else txtStatus.text = "بدون مجوز دوربین بنچمارک اجرا نمی‌شود. مجوز را از تنظیمات برنامه فعال کنید."
    }

    // ───── حالت رقابت منابع: مجوز اعلان (اندروید ۱۳+) و مجوز نمایش روی برنامه‌های دیگر ─────

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> checkOverlayThenStart() } // بدون اعلان هم سرویس کار می‌کند؛ فقط اعلانش دیده نمی‌شود

    private val overlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (canDrawOverlays()) launchContentionMode()
        else txtStatus.text = "برای پنل شناور، مجوز «نمایش روی برنامه‌های دیگر» لازم است."
    }

    private fun onContentionClicked() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkOverlayThenStart()
        }
    }

    private fun checkOverlayThenStart() {
        if (canDrawOverlays()) {
            launchContentionMode()
        } else {
            overlayPermission.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun canDrawOverlays() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun launchContentionMode() {
        val accelerator = when (groupAccelerator.checkedRadioButtonId) {
            R.id.radioGpu -> Accelerator.GPU
            R.id.radioNnapi -> Accelerator.NNAPI
            else -> Accelerator.CPU
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, ContentionService::class.java)
                .putExtra(ContentionService.EXTRA_ACCELERATOR, accelerator.name)
        )
        finish() // دوربین آزاد می‌شود و صفحه برای اجرای بازی خالی می‌ماند
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtHardware = findViewById(R.id.txtHardware)
        txtPrediction = findViewById(R.id.txtPrediction)
        txtFps = findViewById(R.id.txtFps)
        txtInference = findViewById(R.id.txtInference)
        txtSystem = findViewById(R.id.txtSystem)
        txtStatus = findViewById(R.id.txtStatus)
        btnRecord = findViewById(R.id.btnRecord)
        groupAccelerator = findViewById(R.id.groupAccelerator)

        monitor = SystemMonitor(this)
        inferenceExecutor = Executors.newSingleThreadExecutor()

        groupAccelerator.setOnCheckedChangeListener { _, checkedId ->
            if (suppressRadioEvents) return@setOnCheckedChangeListener
            val acc = when (checkedId) {
                R.id.radioGpu -> Accelerator.GPU
                R.id.radioNnapi -> Accelerator.NNAPI
                else -> Accelerator.CPU
            }
            inferenceExecutor.execute { loadEngine(acc) }
        }

        btnRecord.setOnClickListener {
            inferenceExecutor.execute { startRecording() }
        }

        findViewById<Button>(R.id.btnContention).setOnClickListener { onContentionClicked() }

        inferenceExecutor.execute { loadEngine(Accelerator.CPU) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // ───────────────────────── مدل و سخت‌افزار (روی inferenceExecutor) ─────────────────────────

    /** مورد ۵ و ۸: تعویض سخت‌افزار با بازگشت کنترل‌شده به CPU در صورت عدم پشتیبانی */
    private fun loadEngine(requested: Accelerator) {
        engine?.close()
        engine = null
        resetMeasurements()
        recording = false

        var note = ""
        val loaded = try {
            ImageClassifierEngine.create(this, requested)
        } catch (e: Throwable) {
            if (requested == Accelerator.CPU) {
                ui {
                    txtHardware.text = "خطا در بارگذاری مدل: ${e.message}"
                    txtStatus.text = "فایل‌های model.tflite و labels.txt را در پوشه‌ی assets بگذارید."
                }
                return
            }
            note = "${requested.name} روی این گوشی اجرا نشد (${e.message}) ← بازگشت خودکار به CPU"
            try {
                ImageClassifierEngine.create(this, Accelerator.CPU)
            } catch (e2: Throwable) {
                ui { txtHardware.text = "خطا در بارگذاری مدل: ${e2.message}" }
                return
            }
        }
        engine = loaded

        ui {
            txtHardware.text = "پردازشگر: ${loaded.accelerator.title}  |  ${loaded.modelDescription}"
            txtStatus.text = note
            if (loaded.accelerator != requested) {
                suppressRadioEvents = true
                groupAccelerator.check(R.id.radioCpu)
                suppressRadioEvents = false
            }
            btnRecord.isEnabled = true
        }
    }

    private fun resetMeasurements() {
        inferenceStats.reset()
        endToEndStats.reset()
        recorder.reset()
        fpsFrames = 0
        fpsWindowStart = SystemClock.elapsedRealtime()
        currentFps = 0.0
    }

    private fun startRecording() {
        if (engine == null) return
        resetMeasurements()
        recording = true
        ui {
            btnRecord.isEnabled = false
            txtStatus.text = "در حال ثبت… $WARMUP_FRAMES فریم اول (گرم‌کردن) کنار گذاشته می‌شوند."
        }
    }

    private fun finishRecording(eng: ImageClassifierEngine) {
        recording = false
        val dir = getExternalFilesDir(null) ?: filesDir
        val result = try {
            val file = recorder.save(
                dir = dir,
                accelerator = eng.accelerator,
                modelDescription = eng.modelDescription,
                inference = inferenceStats.summary(),
                endToEnd = endToEndStats.summary(),
                deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, SoC ${socName()}",
            )
            "ذخیره شد: ${file.absolutePath}"
        } catch (e: Exception) {
            "ذخیره‌ی CSV ناموفق بود: ${e.message}"
        }
        ui {
            txtStatus.text = result
            btnRecord.isEnabled = true
            Toast.makeText(this, "بنچمارک ${eng.accelerator.name} کامل شد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun socName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        else Build.HARDWARE

    // ───────────────────────── دوربین ─────────────────────────

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
            }

            // مورد ۲: خروجی RGBA مستقیم از CameraX (بدون تبدیل دستی YUV)، با رزولوشن کوچک کافی برای ورودی ۲۲۴
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(inferenceExecutor, ::analyzeFrame) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                txtStatus.text = "خطا در راه‌اندازی دوربین: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ───────────────────────── هسته‌ی بنچمارک (روی inferenceExecutor) ─────────────────────────

    private fun analyzeFrame(image: ImageProxy) {
        val eng = engine
        if (eng == null) {
            image.close()
            return
        }

        val t0 = System.nanoTime()
        val rotation = image.imageInfo.rotationDegrees
        val bitmap = try {
            image.toBitmap()
        } finally {
            image.close() // فریم زود آزاد می‌شود تا دوربین معطل نماند
        }

        // مورد ۱ و ۲: پیش‌پردازش واقعی + استنتاج واقعی روی پیکسل‌های همین فریم
        val input = eng.preprocess(bitmap, rotation)
        val t1 = System.nanoTime()
        val inferenceNs = eng.runInference(input)
        val prediction = eng.topPrediction()
        val t2 = System.nanoTime()

        val preprocessMs = (t1 - t0) / 1e6
        val inferenceMs = inferenceNs / 1e6
        val endToEndMs = (t2 - t0) / 1e6

        // مورد ۷: آمار با حذف warm-up
        inferenceStats.add(inferenceMs)
        endToEndStats.add(endToEndMs)
        val system = monitor.sampleIfDue()

        // FPS = توان عملیاتی کل خط لوله (دوربین + پیش‌پردازش + مدل)
        fpsFrames++
        val now = SystemClock.elapsedRealtime()
        if (now - fpsWindowStart >= 1000) {
            currentFps = fpsFrames * 1000.0 / (now - fpsWindowStart)
            fpsFrames = 0
            fpsWindowStart = now
        }

        // مورد ۹: ثبت در CSV فقط بعد از دوره‌ی گرم‌کردن
        if (recording && !inferenceStats.isWarmingUp) {
            recorder.add(eng.accelerator, preprocessMs, inferenceMs, endToEndMs, prediction, system)
            if (recorder.count >= RECORD_FRAMES) finishRecording(eng)
        }

        if (now - lastUiUpdate >= UI_INTERVAL_MS) {
            lastUiUpdate = now
            renderUi(inferenceMs, prediction, system)
        }
    }

    private fun renderUi(lastInferenceMs: Double, prediction: Prediction, system: SystemSnapshot) {
        val s = inferenceStats.summary()
        val warming = inferenceStats.isWarmingUp
        val recordingProgress = if (recording) "در حال ثبت: ${recorder.count}/$RECORD_FRAMES" else null
        val fps = currentFps

        val inferenceText = if (s == null) {
            String.format(Locale.US, "استنتاج آخر: %.2f ms  (گرم‌کردن…)", lastInferenceMs)
        } else {
            String.format(
                Locale.US,
                "استنتاج — آخر %.2f | میانگین %.2f | میانه %.2f | p90 %.2f ms  (n=%d)",
                lastInferenceMs, s.mean, s.median, s.p90, s.count
            )
        }
        val systemText = String.format(
            Locale.US,
            "CPU برنامه: %.0f%%  |  دما: %s  |  باتری: %.1f°C  |  حافظه native: %.0f MB",
            system.processCpuPercent, system.thermalStatus, system.batteryTempC, system.nativeHeapMb
        )

        ui {
            txtPrediction.text = String.format(Locale.US, "تشخیص: %s  (%.0f%%)", prediction.label, prediction.score * 100)
            txtFps.text = String.format(Locale.US, "FPS خط لوله: %.1f", fps)
            txtInference.text = inferenceText
            txtSystem.text = systemText
            if (recordingProgress != null) txtStatus.text = recordingProgress
            else if (warming && txtStatus.text.isNullOrEmpty()) txtStatus.text = "گرم‌کردن مدل…"
        }
    }

    private inline fun ui(crossinline block: () -> Unit) = runOnUiThread { block() }

    override fun onDestroy() {
        super.onDestroy()
        // بستن مفسر روی همان ترد سازنده، سپس خاموش کردن ترد
        inferenceExecutor.execute {
            engine?.close()
            engine = null
        }
        inferenceExecutor.shutdown()
        inferenceExecutor.awaitTermination(1, TimeUnit.SECONDS)
    }

    companion object {
        private const val WARMUP_FRAMES = 10
        private const val RECORD_FRAMES = 300
        private const val UI_INTERVAL_MS = 250L
    }
}
