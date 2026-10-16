package com.aspar.yolodetector.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLOv8 (خروجی export اولترالیتیکس به TFLite) که FrameDetector را پیاده می‌کند.
 *
 * مراحل هر فریم:
 *  1. letterbox: فریم با حفظ نسبت ابعاد به inputSize×inputSize کوچک و وسط‌چین می‌شود (حاشیه‌ی خاکستری 114)
 *  2. پر کردن ByteBuffer ورودی (float32 یا uint8/int8 کوانتیزه)
 *  3. اجرای مدل → خروجی [1, 4+nc, N] یا [1, N, 4+nc]
 *  4. decode + آستانه‌ی score + NMS کلاس‌به‌کلاس
 *  5. معکوس letterbox: کم کردن padding و تقسیم بر scale → مختصات بیت‌مپ ورودی
 *
 * تبدیل نهایی «بیت‌مپ ضبط → پیکسل واقعی صفحه» در ScreenDetectService انجام می‌شود
 * (ضرب در toScreenX/Y)، چون فقط آن‌جا captureScale معلوم است.
 *
 * همه‌ی بافرها و بیت‌مپ‌ها یک بار ساخته و بازاستفاده می‌شوند؛ در حلقه‌ی فریم تخصیص حافظه نداریم.
 * این کلاس thread-safe نیست؛ فقط از worker thread سرویس صدا زده شود (که همین‌طور است).
 */
class YoloTfliteDetector(
    context: Context,
    modelAsset: String = "yolov8n.tflite",
    private val labels: List<String> = loadLabels(context, "labels.txt"),
    private val scoreThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f,
    private val maxDetections: Int = 50,
    useGpu: Boolean = true,
    numThreads: Int = 4
) : FrameDetector {

    companion object {
        private const val TAG = "YoloTflite"

        fun loadLabels(ctx: Context, asset: String): List<String> =
            runCatching { ctx.assets.open(asset).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() } }
                .getOrElse { Log.w(TAG, "labels.txt not found; using class indices"); emptyList() }

        private fun mapModel(ctx: Context, asset: String): MappedByteBuffer {
            val fd = ctx.assets.openFd(asset)
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    // ---------------------------------------------------------------- مدل
    private var gpu: GpuDelegate? = null
    private val interpreter: Interpreter = run {
        val opts = Interpreter.Options().setNumThreads(numThreads)
        if (useGpu && CompatibilityList().isDelegateSupportedOnThisDevice) {
            gpu = GpuDelegate(CompatibilityList().bestOptionsForThisDevice).also { opts.addDelegate(it) }
        }
        try {
            Interpreter(mapModel(context, modelAsset), opts)
        } catch (t: Throwable) {
            // بعضی دستگاه‌ها با GPU مشکل دارند → برگشت به CPU
            Log.w(TAG, "GPU init failed, falling back to CPU", t)
            gpu?.close(); gpu = null
            Interpreter(mapModel(context, modelAsset), Interpreter.Options().setNumThreads(numThreads))
        }
    }

    // ---------------------------------------------------------------- ورودی
    private val inTensor = interpreter.getInputTensor(0)
    private val inputSize = inTensor.shape()[1]            // NHWC → [1, 640, 640, 3]
    private val inType = inTensor.dataType()
    private val inScale = inTensor.quantizationParams().scale
    private val inZero = inTensor.quantizationParams().zeroPoint
    private val bytesPerChannel = if (inType == DataType.FLOAT32) 4 else 1
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(inputSize * inputSize * 3 * bytesPerChannel).order(ByteOrder.nativeOrder())

    private val lbBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val lbCanvas = Canvas(lbBitmap)
    private val lbPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(inputSize * inputSize)
    private val dstRect = Rect()

    // ---------------------------------------------------------------- خروجی
    private val outTensor = interpreter.getOutputTensor(0)
    private val outShape = outTensor.shape()                // [1, 84, 8400] یا [1, 8400, 84]
    private val channelsFirst = outShape[1] < outShape[2]
    private val numAttrs = if (channelsFirst) outShape[1] else outShape[2]
    private val numAnchors = if (channelsFirst) outShape[2] else outShape[1]
    private val numClasses = numAttrs - 4
    private val outType = outTensor.dataType()
    private val outScale = outTensor.quantizationParams().scale
    private val outZero = outTensor.quantizationParams().zeroPoint
    private val outBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(numAttrs * numAnchors * (if (outType == DataType.FLOAT32) 4 else 1))
            .order(ByteOrder.nativeOrder())
    private val out = FloatArray(numAttrs * numAnchors)

    // کاندیداهای پیش از NMS (بازاستفاده)
    private val candBoxes = ArrayList<RectF>(256)
    private val candScores = ArrayList<Float>(256)
    private val candClasses = ArrayList<Int>(256)

    init {
        Log.i(TAG, "input=$inputSize $inType, output=${outShape.contentToString()} $outType, classes=$numClasses")
        if (labels.isNotEmpty() && labels.size != numClasses)
            Log.w(TAG, "labels.txt has ${labels.size} lines but model has $numClasses classes")
    }

    // =================================================================== API

    override fun detect(frame: Bitmap): List<Detection> =
        detect(frame, Rect(0, 0, frame.width, frame.height))

    /**
     * src = ناحیه‌ی معتبر بیت‌مپ. ImageReader به‌خاطر rowStride ستون‌های padding اضافه دارد؛
     * با دادن src فقط ناحیه‌ی واقعی وارد مدل می‌شود و باکس‌ها نسبت به همان ناحیه برمی‌گردند.
     */
    override fun detect(frame: Bitmap, src: Rect): List<Detection> {
        // ---- ۱. letterbox
        val srcW = src.width().toFloat()
        val srcH = src.height().toFloat()
        val scale = min(inputSize / srcW, inputSize / srcH)
        val newW = (srcW * scale).roundToInt()
        val newH = (srcH * scale).roundToInt()
        val padX = (inputSize - newW) / 2
        val padY = (inputSize - newH) / 2

        lbCanvas.drawColor(Color.rgb(114, 114, 114))
        dstRect.set(padX, padY, padX + newW, padY + newH)
        lbCanvas.drawBitmap(frame, src, dstRect, lbPaint)

        // ---- ۲. پر کردن ورودی
        lbBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        inputBuffer.rewind()
        when (inType) {
            DataType.FLOAT32 -> for (p in pixels) {
                inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f)
                inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)
                inputBuffer.putFloat((p and 0xFF) / 255f)
            }
            DataType.UINT8 -> for (p in pixels) {
                inputBuffer.put(((p shr 16) and 0xFF).toByte())
                inputBuffer.put(((p shr 8) and 0xFF).toByte())
                inputBuffer.put((p and 0xFF).toByte())
            }
            DataType.INT8 -> for (p in pixels) {
                // مقدار نرمال‌شده‌ی 0..1 را با پارامترهای کوانتیزاسیون ورودی به int8 می‌بریم
                fun q(v: Int) = ((v / 255f) / inScale + inZero).roundToInt().coerceIn(-128, 127).toByte()
                inputBuffer.put(q((p shr 16) and 0xFF))
                inputBuffer.put(q((p shr 8) and 0xFF))
                inputBuffer.put(q(p and 0xFF))
            }
            else -> error("Unsupported input type $inType")
        }
        inputBuffer.rewind()

        // ---- ۳. اجرا
        outBuffer.rewind()
        interpreter.run(inputBuffer, outBuffer)
        readOutput()

        // ---- ۴. decode
        candBoxes.clear(); candScores.clear(); candClasses.clear()

        // export‌های جدید اولترالیتیکس مختصات را نرمال (0..1) می‌دهند، قدیمی‌ها به پیکسل ورودی
        val coordMul = if (looksNormalized()) inputSize.toFloat() else 1f

        for (a in 0 until numAnchors) {
            var best = -1
            var bestScore = scoreThreshold
            for (c in 0 until numClasses) {
                val s = at(4 + c, a)
                if (s > bestScore) { bestScore = s; best = c }
            }
            if (best < 0) continue

            val cx = at(0, a) * coordMul
            val cy = at(1, a) * coordMul
            val w = at(2, a) * coordMul
            val h = at(3, a) * coordMul

            // ---- ۵. معکوس letterbox → مختصات بیت‌مپ ورودی
            val l = ((cx - w / 2f) - padX) / scale + src.left
            val t = ((cy - h / 2f) - padY) / scale + src.top
            val r = ((cx + w / 2f) - padX) / scale + src.left
            val b = ((cy + h / 2f) - padY) / scale + src.top

            candBoxes += RectF(
                l.coerceIn(src.left.toFloat(), src.right.toFloat()),
                t.coerceIn(src.top.toFloat(), src.bottom.toFloat()),
                r.coerceIn(src.left.toFloat(), src.right.toFloat()),
                b.coerceIn(src.top.toFloat(), src.bottom.toFloat())
            )
            candScores += bestScore
            candClasses += best
        }

        return nms()
    }

    override fun close() {
        interpreter.close()
        gpu?.close()
        lbBitmap.recycle()
    }

    // ============================================================ کمکی‌ها

    private fun readOutput() {
        outBuffer.rewind()
        if (outType == DataType.FLOAT32) {
            outBuffer.asFloatBuffer().get(out)
        } else {
            val signed = outType == DataType.INT8
            for (i in out.indices) {
                val raw = outBuffer.get().toInt().let { if (signed) it else it and 0xFF }
                out[i] = (raw - outZero) * outScale
            }
        }
    }

    /** مقدار ویژگی attr برای anchor a، مستقل از ترتیب ابعاد خروجی */
    private fun at(attr: Int, a: Int): Float =
        if (channelsFirst) out[attr * numAnchors + a] else out[a * numAttrs + attr]

    private var normalizedCache: Boolean? = null
    private fun looksNormalized(): Boolean = normalizedCache ?: run {
        var maxV = 0f
        for (a in 0 until numAnchors) maxV = max(maxV, max(at(2, a), at(3, a)))
        (maxV <= 2f).also { normalizedCache = it }
    }

    /** NMS کلاس‌به‌کلاس، مرتب‌شده بر اساس score */
    private fun nms(): List<Detection> {
        val order = candScores.indices.sortedByDescending { candScores[it] }
        val removed = BooleanArray(order.size)
        val result = ArrayList<Detection>(min(order.size, maxDetections))

        for (i in order.indices) {
            if (removed[i]) continue
            val idx = order[i]
            val box = candBoxes[idx]
            val cls = candClasses[idx]
            result += Detection(box, labels.getOrElse(cls) { cls.toString() }, candScores[idx])
            if (result.size >= maxDetections) break

            for (j in i + 1 until order.size) {
                if (removed[j]) continue
                val jdx = order[j]
                if (candClasses[jdx] == cls && iou(box, candBoxes[jdx]) > iouThreshold) removed[j] = true
            }
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val iw = min(a.right, b.right) - max(a.left, b.left)
        val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }
}
