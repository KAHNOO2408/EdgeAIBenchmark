package com.aspar.yolodetector.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.aspar.yolodetector.MainActivity
import com.aspar.yolodetector.R
import com.aspar.yolodetector.ml.YoloDetector
import com.aspar.yolodetector.util.FpsCounter
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foreground Service که صفحه‌ی گوشی را با MediaProjection → VirtualDisplay → ImageReader
 * زنده می‌گیرد، هر فریم را به [YoloDetector] می‌دهد و نتیجه را روی [ScreenOverlay] می‌کشد.
 *
 * رشته‌ها:
 *  - Main: چرخه‌ی عمر سرویس، MediaProjection، پنجره‌ی Overlay
 *  - mlThread (یک HandlerThread ثابت): ImageReader callback، ساخت/اجرا/بستن مدل
 *    (GPU Delegate باید روی همان رشته‌ای که ساخته شده اجرا شود).
 */
class ScreenCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var mlThread: HandlerThread
    private lateinit var mlHandler: Handler

    /** Context بصری (WindowContext) برای WindowManager و اندازه‌ی واقعی صفحه. */
    private lateinit var uiContext: Context

    // ---- Main thread ----
    private var projection: MediaProjection? = null
    @Volatile private var overlay: ScreenOverlay? = null

    // ---- فقط روی mlThread ----
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var detector: YoloDetector? = null
    private var detectorUsesGpu = false
    private val fpsCounter = FpsCounter()
    private var captureW = 0
    private var captureH = 0
    private var paddedBitmap: Bitmap? = null   // عرض = rowStride / pixelStride
    private var frameBitmap: Bitmap? = null    // عرض دقیق تصویر (بدون padding)
    private var frameCanvas: Canvas? = null
    private var staging: ByteBuffer? = null
    private val cropRect = Rect()

    private val projectionCallback = object : MediaProjection.Callback() {
        // کاربر از نوار وضعیت/چیپ سیستم اشتراک صفحه را قطع کرد، یا پروجکشن دیگری شروع شد
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mlThread = HandlerThread("yolo-screen-ml").apply { start() }
        mlHandler = Handler(mlThread.looper)
        uiContext = createUiContext()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopSelf()
            // سرویس با intent=null بازسازی شده؛ توکن رضایت کاربر دیگر معتبر نیست
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ شروع

    private fun start(intent: Intent) {
        if (projection != null) return

        // اندروید ۱۴: startForeground با نوع mediaProjection باید *قبل* از getMediaProjection باشد
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val mp = if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(resultCode, data)
            } catch (t: Throwable) {
                Log.e(TAG, "getMediaProjection failed", t)
                null
            }
        } else null

        if (mp == null) {
            ScanBus.publishMessage(getString(R.string.capture_failed))
            stopSelf()
            return
        }

        // اندروید ۱۴: callback باید قبل از createVirtualDisplay ثبت شود
        mp.registerCallback(projectionCallback, mainHandler)
        projection = mp

        val ov = ScreenOverlay(uiContext)
        if (ov.show()) {
            val s = realScreenSize()
            ov.setScreenSize(s.x, s.y)
            overlay = ov
        } else {
            ScanBus.publishMessage(getString(R.string.overlay_missing_running))
        }

        ScanBus.setState(ScanBus.State.RUNNING)
        mlHandler.post {
            ensureDetector()
            startCapture(mp)
        }
    }

    /** روی mlThread. */
    private fun startCapture(mp: MediaProjection) {
        val size = computeCaptureSize()
        val reader = newImageReader(size.width, size.height)
        try {
            virtualDisplay = mp.createVirtualDisplay(
                "YoloScreenCapture",
                size.width, size.height, size.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mlHandler,
            )
        } catch (t: Throwable) {
            // مثلاً SecurityException اگر توکن رضایت دوباره استفاده شده باشد
            Log.e(TAG, "createVirtualDisplay failed", t)
            reader.close()
            imageReader = null
            ScanBus.publishMessage("خطای ضبط صفحه: ${t.message}")
            mainHandler.post { stopSelf() }
        }
    }

    private class CaptureSize(val width: Int, val height: Int, val dpi: Int)

    /**
     * اندازه‌ی VirtualDisplay: همان نسبت صفحه، ولی ضلع بلند حداکثر [CAPTURE_LONG_SIDE].
     * مدل به‌هرحال روی 640 کار می‌کند؛ گرفتن فریم با رزولوشن کامل فقط کپی حافظه را گران می‌کند.
     */
    private fun computeCaptureSize(): CaptureSize {
        val real = realScreenSize()
        val scale = min(1f, CAPTURE_LONG_SIDE / max(real.x, real.y).toFloat())
        val w = ((real.x * scale).roundToInt() / 2) * 2
        val h = ((real.y * scale).roundToInt() / 2) * 2
        val dpi = (uiContext.resources.displayMetrics.densityDpi * scale).roundToInt()
            .coerceAtLeast(1)
        return CaptureSize(w, h, dpi)
    }

    /** روی mlThread. */
    private fun newImageReader(w: Int, h: Int): ImageReader {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, mlHandler)
        imageReader = reader
        captureW = w
        captureH = h
        fpsCounter.reset()
        return reader
    }

    // ------------------------------------------------------------------ چرخش صفحه

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val s = realScreenSize()
        overlay?.setScreenSize(s.x, s.y)
        mlHandler.post { resizeCaptureIfNeeded() }
    }

    /** روی mlThread: با چرخش گوشی، VirtualDisplay و ImageReader را با ابعاد جدید عوض می‌کنیم. */
    private fun resizeCaptureIfNeeded() {
        val vd = virtualDisplay ?: return
        val size = computeCaptureSize()
        if (size.width == captureW && size.height == captureH) return

        val old = imageReader
        val reader = newImageReader(size.width, size.height)
        vd.resize(size.width, size.height, size.dpi)
        vd.surface = reader.surface
        old?.setOnImageAvailableListener(null, null)
        old?.close()
        Log.i(TAG, "capture resized to ${size.width}x${size.height}")
    }

    // ------------------------------------------------------------------ پردازش فریم

    /** روی mlThread — جایگزین ImageAnalysis.Analyzer دوربین. */
    private fun onImageAvailable(reader: ImageReader) {
        if (reader !== imageReader) return // ریدر قدیمی بعد از چرخش

        // acquireLatestImage فریم‌های قدیمی‌تر را دور می‌ریزد = STRATEGY_KEEP_ONLY_LATEST
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return

        // تصویر را فوراً کپی و آزاد می‌کنیم تا VirtualDisplay معطل نماند
        val frame = try {
            copyToBitmap(image)
        } finally {
            image.close()
        }

        val d = ensureDetector() ?: return
        val result = d.detect(frame, ScanSettings.confThreshold, ScanSettings.classFilter())
        val fps = fpsCounter.tick()

        ScanBus.publishFrame(ScanFrame(result, fps, d.delegateName, d.inputWidth, d.inputHeight))

        overlay?.let { ov ->
            val hud = String.format(
                Locale.US, "%.1f FPS | %.0f ms | %s | %d",
                fps, result.totalMs, d.delegateName, result.detections.size
            )
            mainHandler.post { ov.update(result, hud) }
        }
    }

    /**
     * Image (RGBA_8888) → Bitmap با بافرهای قابل استفاده‌ی مجدد.
     * ترتیب بایت‌های RGBA_8888 با حافظه‌ی Bitmap.ARGB_8888 یکی است، پس copyPixelsFromBuffer کافی است.
     * فقط باید padding انتهای هر سطر (rowStride) را حذف کنیم.
     */
    private fun copyToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        val paddedW = rowStride / pixelStride

        var padded = paddedBitmap
        if (padded == null || padded.width != paddedW || padded.height != h) {
            padded?.recycle()
            padded = Bitmap.createBitmap(paddedW, h, Bitmap.Config.ARGB_8888)
            paddedBitmap = padded
        }

        val src = plane.buffer
        val needed = rowStride * h
        if (src.remaining() >= needed) {
            padded.copyPixelsFromBuffer(src)
        } else {
            // برخی دستگاه‌ها سطر آخر را بدون padding می‌دهند → بافر کوتاه‌تر از rowStride*h
            var st = staging
            if (st == null || st.capacity() != needed) {
                st = ByteBuffer.allocateDirect(needed)
                staging = st
            }
            st!!.clear()
            st.put(src)
            st.rewind()
            padded.copyPixelsFromBuffer(st)
        }

        if (paddedW == w) return padded

        var frame = frameBitmap
        if (frame == null || frame.width != w || frame.height != h) {
            frame?.recycle()
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            frameBitmap = frame
            frameCanvas = Canvas(frame)
        }
        cropRect.set(0, 0, w, h)
        frameCanvas!!.drawBitmap(padded, cropRect, cropRect, null)
        return frame!!
    }

    /** روی mlThread: مدل را می‌سازد یا اگر سوئیچ GPU عوض شده، دوباره می‌سازد. */
    private fun ensureDetector(): YoloDetector? {
        val wantGpu = ScanSettings.useGpu
        detector?.let { if (detectorUsesGpu == wantGpu) return it }

        detector?.close()
        detector = null
        fpsCounter.reset()
        return try {
            YoloDetector(this, MainActivity.MODEL_FILE, MainActivity.LABELS_FILE, useGpu = wantGpu)
                .also {
                    it.warmUp()
                    detector = it
                    detectorUsesGpu = wantGpu
                }
        } catch (t: Throwable) {
            Log.e(TAG, "model load failed", t)
            ScanBus.publishMessage("خطا در بارگذاری مدل:\n${t.message}")
            mainHandler.post { stopSelf() }
            null
        }
    }

    // ------------------------------------------------------------------ توقف

    override fun onDestroy() {
        ScanBus.setState(ScanBus.State.STOPPED)

        overlay?.hide()
        overlay = null

        val mp = projection
        projection = null
        mp?.unregisterCallback(projectionCallback)

        // آزادسازی روی همان رشته‌ای که مدل و ImageReader رویش کار می‌کنند
        mlHandler.post {
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mp?.stop()
            detector?.close()
            detector = null
            paddedBitmap?.recycle(); paddedBitmap = null
            frameBitmap?.recycle(); frameBitmap = null
            frameCanvas = null
            staging = null
        }
        mlThread.quitSafely()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ کمکی‌ها

    private fun createUiContext(): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return this
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    /** اندازه‌ی واقعی کل نمایشگر (با status/navigation bar) در جهت فعلی. */
    private fun realScreenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = uiContext.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val display = getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)
            Point().also {
                @Suppress("DEPRECATION")
                display.getRealSize(it)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.scan_channel), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_scan)
            .setContentTitle(getString(R.string.scan_notif_title))
            .setContentText(getString(R.string.scan_notif_text))
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop_scan), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val ACTION_START = "com.aspar.yolodetector.action.START_SCAN"
        private const val ACTION_STOP = "com.aspar.yolodetector.action.STOP_SCAN"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_scan"
        private const val NOTIFICATION_ID = 42

        /** ضلع بلند فریم گرفته‌شده؛ برای مدل 640 همین کافی است. */
        private const val CAPTURE_LONG_SIDE = 960f

        /** [data] همان Intent برگشتی از createScreenCaptureIntent است (فقط یک بار قابل استفاده). */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}
