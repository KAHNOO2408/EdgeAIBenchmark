package com.aspar.yolodetector.screen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.aspar.yolodetector.gesture.AimController
import java.util.concurrent.atomic.AtomicBoolean

/**
 * خروجی مدل شما. اگر کلاس Detector فعلی‌تان امضای دیگری دارد،
 * یک آداپتور کوچک بنویسید که این interface را پیاده کند.
 * box در مختصات بیت‌مپ ورودی (همان فریم صفحه) برگردانده شود.
 */
data class Detection(val box: RectF, val label: String, val score: Float)

interface FrameDetector {
    fun detect(frame: Bitmap): List<Detection>

    /** فقط ناحیه‌ی src از بیت‌مپ را بررسی کن (برای حذف ستون‌های padding ImageReader) */
    fun detect(frame: Bitmap, src: Rect): List<Detection> =
        detect(frame).filter { src.contains(it.box.centerX().toInt(), it.box.centerY().toInt()) }

    fun close() {}
}

/**
 * سرویس پیش‌زمینه: MediaProjection → ImageReader → YOLO → AimController → SmoothGestureService
 *
 * - فریم‌ها روی یک HandlerThread جدا پردازش می‌شوند تا UI و ژست‌ها کند نشوند.
 * - اگر استنتاج قبلی هنوز تمام نشده، فریم جدید دور ریخته می‌شود (backpressure).
 * - ضبط با رزولوشن کاهش‌یافته (captureScale) انجام می‌شود و باکس‌ها به پیکسل واقعی صفحه برگردانده می‌شوند.
 */
class ScreenDetectService : Service() {

    companion object {
        private const val TAG = "ScreenDetect"
        private const val CHANNEL = "screen_detect"
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_DATA = "data"

        /** کارخانه‌ی Detector؛ از Activity یا Application مقدار بدهید */
        var detectorFactory: ((Context) -> FrameDetector)? = null
        var targetLabels: Set<String> = setOf("player")
        var minScore: Float = 0.5f
        /** نسبت رزولوشن ضبط به صفحه (0.5 = نصف؛ سریع‌تر و برای YOLO کافی) */
        var captureScale: Float = 0.5f

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ScreenDetectService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ScreenDetectService::class.java))
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var detector: FrameDetector? = null
    private var aim: AimController? = null

    private val worker = HandlerThread("yolo-worker").apply { start() }
    private val workerHandler = Handler(worker.looper)
    private val busy = AtomicBoolean(false)
    private var reusable: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // اندروید ۱۴+: startForeground باید قبل از getMediaProjection صدا زده شود
        startAsForeground()

        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val rc = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val dataOrNull: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_DATA)
        }
        val data = dataOrNull ?: run { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(rc, data)?.also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, null)
        }
        if (projection == null) { stopSelf(); return START_NOT_STICKY }

        detector = detectorFactory?.invoke(this)
            ?: run { Log.e(TAG, "detectorFactory not set"); stopSelf(); return START_NOT_STICKY }

        setupCapture()
        return START_NOT_STICKY
    }

    private fun setupCapture() {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val capW = (screenW * captureScale).toInt() and 0x7FFFFFFE   // زوج
        val capH = (screenH * captureScale).toInt() and 0x7FFFFFFE
        val toScreenX = screenW.toFloat() / capW
        val toScreenY = screenH.toFloat() / capH
        val validRegion = Rect(0, 0, capW, capH)

        aim = AimController(screenW, screenH)

        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (!busy.compareAndSet(false, true)) { image.close(); return@setOnImageAvailableListener }
                try {
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * capW
                    val bmpW = capW + rowPadding / plane.pixelStride
                    val bmp = reusable?.takeIf { it.width == bmpW && it.height == capH }
                        ?: Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888).also { reusable = it }
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    image.close()

                    // فقط ناحیه‌ی واقعی (بدون ستون‌های padding) وارد مدل می‌شود
                    val dets = detector!!.detect(bmp, validRegion)
                    val boxes = dets.asSequence()
                        .filter { it.score >= minScore && it.label in targetLabels }
                        .map {
                            RectF(it.box.left * toScreenX, it.box.top * toScreenY,
                                  it.box.right * toScreenX, it.box.bottom * toScreenY)
                        }.toList()
                    aim?.onDetections(boxes)
                } catch (t: Throwable) {
                    Log.e(TAG, "frame error", t)
                    runCatching { image.close() }
                } finally {
                    busy.set(false)
                }
            }, workerHandler)
        }

        virtualDisplay = projection!!.createVirtualDisplay(
            "yolo-screen", capW, capH, (dm.densityDpi * captureScale).toInt(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, workerHandler
        )
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen detection", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("YoloDetector")
            .setContentText("اسکن صفحه فعال است")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        aim = null
        com.aspar.yolodetector.gesture.SmoothGestureService.instance?.release()
        virtualDisplay?.release()
        reader?.close()
        projection?.stop()
        workerHandler.post { detector?.close(); reusable?.recycle() }
        worker.quitSafely()
        super.onDestroy()
    }
}
