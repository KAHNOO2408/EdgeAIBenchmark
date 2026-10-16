package com.academic.edgebenchmark

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * حالت «رقابت منابع»: مدل در یک سرویس پیش‌زمینه با نرخ ثابت اجرا می‌شود و پنل شناور
 * زمان استنتاج را در دو مرحله مقایسه می‌کند:
 *   پایه   = برنامه در پس‌زمینه، بدون بار سنگین (مثلاً صفحه‌ی Home)
 *   زیر بار = همان شرایط، ولی یک بازی/برنامه‌ی سنگین در جلو اجرا می‌شود
 * این سرویس به FPS یا حافظه‌ی برنامه‌ی دیگر دسترسی ندارد و ادعایش را هم نمی‌کند.
 */
class ContentionService : Service() {

    enum class Phase(val title: String) {
        BASELINE("پایه"),
        LOAD("زیر بار"),
    }

    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private lateinit var txtMode: TextView
    private lateinit var txtLive: TextView
    private lateinit var txtCompare: TextView
    private lateinit var txtSystem: TextView
    private lateinit var txtStatus: TextView
    private lateinit var btnPhase: Button

    private val main = Handler(Looper.getMainLooper())

    /** تمام کار مدل روی همین یک ترد: ساخت، اجرا، بستن (قانون GPU Delegate) */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)      // دیگر ترد‌ها با این پرچم حلقه را متوقف می‌کنند
    private val saveRequested = AtomicBoolean(false)
    @Volatile private var phase = Phase.BASELINE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // باید در چند ثانیه‌ی اول بعد از startForegroundService صدا زده شود
        startAsForeground()
        if (running.getAndSet(true)) return START_NOT_STICKY // اجرای تکراری

        val accelerator = intent?.getStringExtra(EXTRA_ACCELERATOR)
            ?.let { runCatching { Accelerator.valueOf(it) }.getOrNull() }
            ?: Accelerator.CPU

        showOverlay()
        worker.execute { benchmarkLoop(accelerator) }
        return START_NOT_STICKY
    }

    // ───────────────────────── حلقه‌ی بنچمارک (روی worker) ─────────────────────────

    private fun benchmarkLoop(requested: Accelerator) {
        var note = ""
        val engine = try {
            ImageClassifierEngine.create(this, requested)
        } catch (e: Throwable) {
            if (requested != Accelerator.CPU) {
                note = "${requested.name} اجرا نشد ← CPU"
                runCatching { ImageClassifierEngine.create(this, Accelerator.CPU) }.getOrNull()
            } else null
        }
        if (engine == null) {
            ui { txtStatus.text = "خطا: model.tflite در assets پیدا یا بارگذاری نشد" }
            running.set(false)
            return
        }

        try {
            // تصاویر یک بار پیش‌پردازش می‌شوند تا فقط خودِ استنتاج سنجیده شود
            val (bitmaps, synthetic) = loadTestImages()
            val inputs = bitmaps.map { engine.preprocess(it, 0) }

            val monitor = SystemMonitor(this)
            val stats = EnumMap<Phase, BenchmarkStats>(Phase::class.java).apply {
                Phase.values().forEach { put(it, BenchmarkStats(warmupFrames = WARMUP_SAMPLES)) }
            }
            val recent = ArrayDeque<Double>()
            val rows = StringBuilder()
            var rowCount = 0
            var lastPhase = phase
            var index = 0
            var lastUi = 0L

            ui {
                txtMode.text = "${engine.accelerator.title}\n${engine.modelDescription}"
                txtStatus.text = buildString {
                    if (note.isNotEmpty()) append(note).append('\n')
                    if (synthetic) append("تصویر آزمون در assets/test_images نبود؛ از تصویر مصنوعی استفاده شد")
                }
            }

            while (running.get()) {
                val cycleStart = SystemClock.elapsedRealtime()
                val currentPhase = phase
                if (currentPhase != lastPhase) {
                    recent.clear() // پنجره‌ی زنده با تغییر مرحله از نو شروع می‌شود
                    lastPhase = currentPhase
                }

                val inferenceMs = engine.runInference(inputs[index % inputs.size]) / 1e6
                index++

                val phaseStats = stats.getValue(currentPhase)
                phaseStats.add(inferenceMs)
                recent.addLast(inferenceMs)
                if (recent.size > RECENT_WINDOW) recent.removeFirst()
                val sys = monitor.sampleIfDue()

                if (!phaseStats.isWarmingUp) {
                    rowCount++
                    rows.append(
                        String.format(
                            Locale.US, "%d,%d,%s,%s,%.3f,%.1f,%s,%.1f,%.1f\n",
                            rowCount, System.currentTimeMillis(), currentPhase.name,
                            engine.accelerator.name, inferenceMs, sys.processCpuPercent,
                            sys.thermalStatus, sys.batteryTempC, sys.nativeHeapMb,
                        )
                    )
                }

                if (saveRequested.getAndSet(false)) {
                    val message = save(rows, stats, engine)
                    ui { txtStatus.text = message }
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastUi >= UI_INTERVAL_MS) {
                    lastUi = now
                    render(inferenceMs, median(recent), currentPhase, stats, sys)
                }

                // نرخ نمونه‌برداری ثابت؛ خودِ بنچمارک نباید عامل اصلی بار باشد
                val wait = SAMPLE_PERIOD_MS - (SystemClock.elapsedRealtime() - cycleStart)
                if (wait > 0) Thread.sleep(wait)
            }
        } catch (_: InterruptedException) {
            // توقف عادی
        } finally {
            engine.close() // روی همان تردی که ساخته شد
        }
    }

    private fun render(
        lastMs: Double,
        recentMedian: Double,
        current: Phase,
        stats: Map<Phase, BenchmarkStats>,
        sys: SystemSnapshot,
    ) {
        val base = stats[Phase.BASELINE]?.summary()
        val load = stats[Phase.LOAD]?.summary()
        val compare = buildString {
            append("میانه پایه: ").append(base?.let { fmt(it.median) } ?: "—")
            append(" | زیر بار: ").append(load?.let { fmt(it.median) } ?: "—")
            if (base != null && load != null) {
                append(String.format(Locale.US, "\nکندشدن: %+.0f%% (میانه) | %+.0f%% (p90)",
                    (load.median / base.median - 1) * 100, (load.p90 / base.p90 - 1) * 100))
            }
        }
        val live = String.format(Locale.US, "مرحله %s — آخر %.1f ms | میانه اخیر %.1f ms",
            current.title, lastMs, recentMedian)
        val system = String.format(Locale.US, "CPU برنامه %.0f%% | دما: %s | باتری %.1f°C",
            sys.processCpuPercent, sys.thermalStatus, sys.batteryTempC)
        ui {
            txtLive.text = live
            txtCompare.text = compare
            txtSystem.text = system
        }
    }

    private fun save(
        rows: CharSequence,
        stats: Map<Phase, BenchmarkStats>,
        engine: ImageClassifierEngine,
    ): String = try {
        val dir = getExternalFilesDir(null) ?: filesDir
        val base = "contention_${engine.accelerator.name}_${System.currentTimeMillis()}"
        File(dir, "$base.csv").writeText(
            "sample,timestamp_ms,phase,accelerator,inference_ms,process_cpu_pct," +
                "thermal_status,battery_temp_c,native_heap_mb\n$rows"
        )
        File(dir, "${base}_summary.csv").writeText(buildString {
            append("phase,count,mean_ms,median_ms,p90_ms,min_ms,max_ms,std_ms\n")
            stats.forEach { (p, s) ->
                s.summary()?.let {
                    append(String.format(Locale.US, "%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f\n",
                        p.name, it.count, it.mean, it.median, it.p90, it.min, it.max, it.stdDev))
                }
            }
            val b = stats[Phase.BASELINE]?.summary()
            val l = stats[Phase.LOAD]?.summary()
            if (b != null && l != null) {
                append(String.format(Locale.US, "\n# slowdown_median_pct,%.1f\n# slowdown_p90_pct,%.1f\n",
                    (l.median / b.median - 1) * 100, (l.p90 / b.p90 - 1) * 100))
            }
            append("# device,\"${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\"\n")
            append("# model,\"${engine.modelDescription}\"\n")
            append("# sample_period_ms,$SAMPLE_PERIOD_MS\n")
        })
        "ذخیره شد: $base"
    } catch (e: Exception) {
        "ذخیره ناموفق: ${e.message}"
    }

    /** تصاویر آزمون از assets/test_images؛ در نبودشان، تصاویر مصنوعی با بذر ثابت (تکرارپذیر) */
    private fun loadTestImages(): Pair<List<Bitmap>, Boolean> {
        val names = runCatching { assets.list("test_images")?.toList() }.getOrNull().orEmpty()
            .filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val real = names.mapNotNull { name ->
            runCatching { assets.open("test_images/$name").use { BitmapFactory.decodeStream(it, null, opts) } }
                .getOrNull()
        }
        if (real.isNotEmpty()) return real to false

        val rnd = Random(42)
        val synthetic = List(5) {
            val w = 320
            val h = 320
            val pixels = IntArray(w * h) { rnd.nextInt() or (0xFF shl 24) }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }
        return synthetic to true
    }

    // ───────────────────────── پنل شناور (ترد UI) ─────────────────────────

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.floating_contention, null)
        txtMode = view.findViewById(R.id.txtMode)
        txtLive = view.findViewById(R.id.txtLive)
        txtCompare = view.findViewById(R.id.txtCompare)
        txtSystem = view.findViewById(R.id.txtSystem)
        txtStatus = view.findViewById(R.id.txtStatus)
        btnPhase = view.findViewById(R.id.btnPhase)

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 160
        }

        // جابه‌جایی پنل با کشیدن نوار عنوان، تا روی دکمه‌های بازی نماند
        view.findViewById<View>(R.id.txtHandle).setOnTouchListener(object : View.OnTouchListener {
            var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (e.rawX - touchX).toInt()
                        params.y = startY + (e.rawY - touchY).toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                }
                return true
            }
        })

        btnPhase.setOnClickListener {
            phase = if (phase == Phase.BASELINE) Phase.LOAD else Phase.BASELINE
            btnPhase.text = "مرحله: ${phase.title}"
        }
        view.findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveRequested.set(true)
            txtStatus.text = "در حال ذخیره…"
        }
        view.findViewById<Button>(R.id.btnClose).setOnClickListener { stopSelf() }

        windowManager.addView(view, params)
        overlay = view
    }

    private fun startAsForeground() {
        val channelId = "contention_benchmark"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "بنچمارک رقابت منابع", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("بنچمارک هوش مصنوعی لبه در حال اجراست")
            .setContentText("سنجش اثر برنامه‌های دیگر بر زمان استنتاج مدل")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        running.set(false)   // حلقه در دور بعد خارج می‌شود و مدل را روی ترد خودش می‌بندد
        worker.shutdown()
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        super.onDestroy()
    }

    private fun ui(block: () -> Unit) {
        main.post { if (overlay != null) block() }
    }

    private fun median(values: Collection<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f ms", v)

    companion object {
        const val EXTRA_ACCELERATOR = "accelerator"
        private const val NOTIFICATION_ID = 7
        private const val WARMUP_SAMPLES = 10
        private const val RECENT_WINDOW = 30
        private const val SAMPLE_PERIOD_MS = 200L   // ۵ نمونه در ثانیه
        private const val UI_INTERVAL_MS = 300L
    }
}
