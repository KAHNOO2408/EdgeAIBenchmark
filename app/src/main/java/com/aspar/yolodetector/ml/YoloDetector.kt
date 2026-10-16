package com.aspar.yolodetector.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * آشکارساز YOLOv8 با Interpreter خام TFLite.
 *
 * - ورودی: NHWC [1, H, W, 3] یا NCHW [1, 3, H, W] (اکسپورت جدید LiteRT)، RGB نرمال‌شده 0..1 (float32/float16) یا کوانتیزه (uint8/int8)
 * - خروجی: [1, 4+C, N] (پیش‌فرض اکسپورت Ultralytics) یا [1, N, 4+C] — هر دو پشتیبانی می‌شود
 * - مختصات خروجی چه نرمال (0..1) چه پیکسلی باشد، خودکار تشخیص داده می‌شود
 *
 * مهم: GPU Delegate باید روی همان رشته‌ای ساخته و اجرا شود؛ پس تمام فراخوانی‌های
 * این کلاس (ساخت، detect، close) را روی یک رشته‌ی ثابت انجام دهید.
 */
class YoloDetector(
    context: Context,
    modelPath: String,
    labelsPath: String,
    useGpu: Boolean = true,
    numThreads: Int = 4,
) : Closeable {

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null

    val delegateName: String
    val labels: List<String>
    val inputWidth: Int
    val inputHeight: Int
    val numClasses: Int

    // ---- ورودی ----
    private val inputType: DataType
    private val inputNchw: Boolean
    private val inScale: Float
    private val inZeroPoint: Int
    private val inputBuffer: ByteBuffer
    private val inputFloats: FloatArray
    private val pixels: IntArray
    private val letterboxBitmap: Bitmap
    private val letterboxCanvas: Canvas
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()

    // ---- خروجی ----
    private val outputType: DataType
    private val outScale: Float
    private val outZeroPoint: Int
    private val outputBuffer: ByteBuffer
    private val output: FloatArray
    private val channelsFirst: Boolean
    private val numChannels: Int
    private val numAnchors: Int
    private val allClasses: IntArray

    init {
        val model = loadModel(context, modelPath)

        // ---------- ساخت Interpreter: اول GPU، در صورت شکست CPU ----------
        var interp: Interpreter? = null
        var name = ""
        if (useGpu) {
            var delegate: GpuDelegate? = null
            try {
                val compat = CompatibilityList()
                if (compat.isDelegateSupportedOnThisDevice) {
                    val gpuOptions = compat.bestOptionsForThisDevice
                    gpuOptions.setPrecisionLossAllowed(true) // FP16 روی GPU
                    delegate = GpuDelegate(gpuOptions)
                    interp = Interpreter(model, Interpreter.Options().addDelegate(delegate))
                    gpuDelegate = delegate
                    name = "GPU"
                } else {
                    Log.w(TAG, "GPU delegate روی این دستگاه پشتیبانی نمی‌شود")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ساخت GPU delegate ناموفق بود، برگشت به CPU", t)
                delegate?.close()
                gpuDelegate = null
                interp = null
            }
        }
        if (interp == null) {
            interp = Interpreter(
                model,
                Interpreter.Options().setNumThreads(numThreads).setUseXNNPACK(true)
            )
            name = if (useGpu) "CPU x$numThreads (GPU ناموجود)" else "CPU x$numThreads"
        }
        interpreter = interp
        delegateName = name

        // ---------- ورودی ----------
        val inTensor = interpreter.getInputTensor(0)
        val inShape = inTensor.shape() // [1, H, W, 3] یا [1, 3, H, W]
        require(inShape.size == 4 && (inShape[3] == 3 || inShape[1] == 3)) {
            "شکل ورودی پشتیبانی نمی‌شود: ${inShape.contentToString()} (NHWC یا NCHW انتظار می‌رود)"
        }
        inputNchw = inShape[3] != 3
        inputHeight = if (inputNchw) inShape[2] else inShape[1]
        inputWidth = if (inputNchw) inShape[3] else inShape[2]
        inputType = inTensor.dataType()
        inScale = inTensor.quantizationParams().scale
        inZeroPoint = inTensor.quantizationParams().zeroPoint
        inputBuffer = ByteBuffer.allocateDirect(inTensor.numBytes()).order(ByteOrder.nativeOrder())
        inputFloats = FloatArray(inputWidth * inputHeight * 3)
        pixels = IntArray(inputWidth * inputHeight)
        letterboxBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        letterboxCanvas = Canvas(letterboxBitmap)

        // ---------- خروجی ----------
        val outTensor = interpreter.getOutputTensor(0)
        val outShape = outTensor.shape() // [1, 84, 8400] یا [1, 8400, 84]
        require(outShape.size == 3) { "شکل خروجی پشتیبانی نمی‌شود: ${outShape.contentToString()}" }
        channelsFirst = outShape[1] < outShape[2]
        numChannels = min(outShape[1], outShape[2])
        numAnchors = max(outShape[1], outShape[2])
        numClasses = numChannels - 4
        outputType = outTensor.dataType()
        outScale = outTensor.quantizationParams().scale
        outZeroPoint = outTensor.quantizationParams().zeroPoint
        outputBuffer = ByteBuffer.allocateDirect(outTensor.numBytes()).order(ByteOrder.nativeOrder())
        output = FloatArray(outShape[1] * outShape[2])
        allClasses = IntArray(numClasses) { it }

        val fileLabels = context.assets.open(labelsPath).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        labels = if (fileLabels.size == numClasses) fileLabels else {
            Log.w(TAG, "تعداد لیبل‌ها (${fileLabels.size}) با کلاس‌های مدل ($numClasses) نمی‌خواند")
            List(numClasses) { fileLabels.getOrNull(it) ?: "class_$it" }
        }

        Log.i(
            TAG, "Model loaded: in=${inShape.contentToString()} $inputType, " +
                "out=${outShape.contentToString()} $outputType, delegate=$delegateName"
        )
    }

    /** اولین اجرا روی GPU کند است (کامپایل شیدرها)؛ قبل از اندازه‌گیری گرم کنید. */
    fun warmUp(runs: Int = 2) {
        inputBuffer.clear()
        while (inputBuffer.hasRemaining()) inputBuffer.put(0)
        repeat(runs) {
            inputBuffer.rewind()
            outputBuffer.rewind()
            interpreter.run(inputBuffer, outputBuffer)
        }
    }

    fun detect(
        bitmap: Bitmap,
        confThreshold: Float = 0.35f,
        classFilter: Set<Int>? = null,
        iouThreshold: Float = 0.45f,
        maxDetections: Int = 100,
    ): DetectionResult {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val lb = preprocess(bitmap)
        val t1 = SystemClock.elapsedRealtimeNanos()

        inputBuffer.rewind()
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        val t2 = SystemClock.elapsedRealtimeNanos()

        readOutput()
        val dets = postprocess(
            lb, bitmap.width, bitmap.height, confThreshold, classFilter, iouThreshold, maxDetections
        )
        val t3 = SystemClock.elapsedRealtimeNanos()

        return DetectionResult(
            detections = dets,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            preprocessMs = (t1 - t0) / 1e6f,
            inferenceMs = (t2 - t1) / 1e6f,
            postprocessMs = (t3 - t2) / 1e6f,
        )
    }

    // ----------------------------------------------------------------------------------------

    private class Letterbox(val scale: Float, val padX: Float, val padY: Float)

    /** Letterbox با حاشیه‌ی خاکستری 114 (مثل Ultralytics) و پر کردن بافر ورودی. */
    private fun preprocess(src: Bitmap): Letterbox {
        val scale = min(inputWidth / src.width.toFloat(), inputHeight / src.height.toFloat())
        val nw = src.width * scale
        val nh = src.height * scale
        val padX = (inputWidth - nw) / 2f
        val padY = (inputHeight - nh) / 2f

        letterboxCanvas.drawColor(Color.rgb(114, 114, 114))
        dstRect.set(padX, padY, padX + nw, padY + nh)
        letterboxCanvas.drawBitmap(src, null, dstRect, drawPaint)
        letterboxBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        // ابتدا مقادیر 0..1 را در inputFloats با چیدمان مدل (NHWC یا NCHW) می‌چینیم
        val plane = inputWidth * inputHeight
        if (inputNchw) {
            for (i in pixels.indices) {
                val p = pixels[i]
                inputFloats[i] = ((p shr 16) and 0xFF) / 255f
                inputFloats[plane + i] = ((p shr 8) and 0xFF) / 255f
                inputFloats[2 * plane + i] = (p and 0xFF) / 255f
            }
        } else {
            var j = 0
            for (p in pixels) {
                inputFloats[j++] = ((p shr 16) and 0xFF) / 255f
                inputFloats[j++] = ((p shr 8) and 0xFF) / 255f
                inputFloats[j++] = (p and 0xFF) / 255f
            }
        }

        inputBuffer.rewind()
        when (inputType) {
            DataType.FLOAT32 -> inputBuffer.asFloatBuffer().put(inputFloats)
            DataType.UINT8 -> for (v in inputFloats) {
                inputBuffer.put((v * 255f).roundToInt().toByte())
            }
            DataType.INT8 -> for (v in inputFloats) inputBuffer.put(quantizeInt8(v))
            else -> error("نوع ورودی پشتیبانی نمی‌شود: $inputType")
        }
        inputBuffer.rewind()
        return Letterbox(scale, padX, padY)
    }

    private fun quantizeInt8(v: Float): Byte =
        (v / inScale + inZeroPoint).roundToInt().coerceIn(-128, 127).toByte()

    private fun readOutput() {
        outputBuffer.rewind()
        when (outputType) {
            DataType.FLOAT32 -> outputBuffer.asFloatBuffer().get(output)
            DataType.UINT8 -> for (i in output.indices) {
                output[i] = ((outputBuffer.get().toInt() and 0xFF) - outZeroPoint) * outScale
            }
            DataType.INT8 -> for (i in output.indices) {
                output[i] = (outputBuffer.get().toInt() - outZeroPoint) * outScale
            }
            else -> error("نوع خروجی پشتیبانی نمی‌شود: $outputType")
        }
    }

    private fun idx(channel: Int, anchor: Int): Int =
        if (channelsFirst) channel * numAnchors + anchor else anchor * numChannels + channel

    private class Candidate(
        val cx: Float, val cy: Float, val w: Float, val h: Float,
        val classId: Int, val score: Float,
    )

    private fun postprocess(
        lb: Letterbox,
        imgW: Int,
        imgH: Int,
        confThreshold: Float,
        classFilter: Set<Int>?,
        iouThreshold: Float,
        maxDetections: Int,
    ): List<Detection> {
        val classes = classFilter?.filter { it in 0 until numClasses }?.toIntArray() ?: allClasses
        if (classes.isEmpty()) return emptyList()

        val candidates = ArrayList<Candidate>()
        var maxCoord = 0f
        for (i in 0 until numAnchors) {
            var best = -1
            var bestScore = confThreshold
            for (c in classes) {
                val s = output[idx(4 + c, i)]
                if (s > bestScore) {
                    bestScore = s; best = c
                }
            }
            if (best < 0) continue
            val cx = output[idx(0, i)]
            val cy = output[idx(1, i)]
            val w = output[idx(2, i)]
            val h = output[idx(3, i)]
            candidates.add(Candidate(cx, cy, w, h, best, bestScore))
            maxCoord = max(maxCoord, max(cx + w / 2f, cy + h / 2f))
        }
        if (candidates.isEmpty()) return emptyList()

        // اکسپورت‌های جدید Ultralytics مختصات را نرمال (0..1) می‌دهند، قدیمی‌ها پیکسلی
        val normalized = maxCoord <= 1.5f
        val sx = if (normalized) inputWidth.toFloat() else 1f
        val sy = if (normalized) inputHeight.toFloat() else 1f

        candidates.sortByDescending { it.score }

        val boxes = candidates.map { c ->
            val x1 = ((c.cx - c.w / 2f) * sx - lb.padX) / lb.scale
            val y1 = ((c.cy - c.h / 2f) * sy - lb.padY) / lb.scale
            val x2 = ((c.cx + c.w / 2f) * sx - lb.padX) / lb.scale
            val y2 = ((c.cy + c.h / 2f) * sy - lb.padY) / lb.scale
            RectF(
                x1.coerceIn(0f, imgW.toFloat()), y1.coerceIn(0f, imgH.toFloat()),
                x2.coerceIn(0f, imgW.toFloat()), y2.coerceIn(0f, imgH.toFloat())
            )
        }

        // NMS به تفکیک کلاس
        val suppressed = BooleanArray(candidates.size)
        val result = ArrayList<Detection>()
        for (i in candidates.indices) {
            if (suppressed[i]) continue
            val ci = candidates[i]
            result.add(Detection(boxes[i], ci.classId, labels[ci.classId], ci.score))
            if (result.size >= maxDetections) break
            for (j in i + 1 until candidates.size) {
                if (!suppressed[j] && candidates[j].classId == ci.classId &&
                    iou(boxes[i], boxes[j]) > iouThreshold
                ) suppressed[j] = true
            }
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
        gpuDelegate = null
    }

    companion object {
        private const val TAG = "YoloDetector"

        private fun loadModel(context: Context, path: String): MappedByteBuffer =
            context.assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(
                        FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
                    )
                }
            }
    }
}
