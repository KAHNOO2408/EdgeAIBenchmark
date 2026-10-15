package com.academic.edgebenchmark

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.Closeable

/** مورد ۸: سه مسیر سخت‌افزاری قابل مقایسه */
enum class Accelerator(val title: String) {
    CPU("CPU — XNNPACK، ۴ رشته"),
    GPU("GPU Delegate (OpenCL/OpenGL)"),
    NNAPI("NNAPI — NPU/DSP/GPU بسته به گوشی"),
}

data class Prediction(val label: String, val score: Float)

/**
 * موتور طبقه‌بندی تصویر.
 * قانون مهم (مورد ۴): هر نمونه باید روی همان تردی ساخته، اجرا و بسته شود.
 * در MainActivity همه‌ی این کارها روی cameraExecutor (یک ترد ثابت) انجام می‌شود.
 */
class ImageClassifierEngine private constructor(
    private val interpreter: Interpreter,
    private val delegate: AutoCloseable?,
    val accelerator: Accelerator,
    private val labels: List<String>,
) : Closeable {

    // مورد ۱۰: نوع و ابعاد ورودی از خود مدل خوانده می‌شود، نه هاردکد
    private val inputTensor = interpreter.getInputTensor(0)
    private val inputType: DataType = inputTensor.dataType()
    private val inputHeight = inputTensor.shape()[1]
    private val inputWidth = inputTensor.shape()[2]

    // مورد ۳: بافر خروجی بر اساس شکل و نوع خروجی واقعی مدل
    private val outputTensor = interpreter.getOutputTensor(0)
    private val output = TensorBuffer.createFixedSize(outputTensor.shape(), outputTensor.dataType())
    private val outScale = outputTensor.quantizationParams().scale
    private val outZeroPoint = outputTensor.quantizationParams().zeroPoint

    val isQuantized: Boolean get() = inputType == DataType.UINT8
    val modelDescription: String
        get() = "ورودی ${inputWidth}×${inputHeight}، " + if (isQuantized) "کوانتیزه UINT8" else "شناور FLOAT32"

    private var cachedProcessor: ImageProcessor? = null
    private var cachedKey = ""

    /** مورد ۲: برش مرکزی مربعی → تغییر اندازه → اصلاح چرخش → نرمال‌سازی (فقط مدل float) */
    fun preprocess(bitmap: Bitmap, rotationDegrees: Int): TensorImage {
        val key = "${bitmap.width}x${bitmap.height}@$rotationDegrees"
        val processor = if (key == cachedKey) cachedProcessor!! else {
            val side = minOf(bitmap.width, bitmap.height)
            val builder = ImageProcessor.Builder()
                .add(ResizeWithCropOrPadOp(side, side))
                .add(ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
                .add(Rot90Op(-rotationDegrees / 90))
            // MobileNet شناور ورودی را در بازه‌ی [-1, 1] می‌خواهد
            if (!isQuantized) builder.add(NormalizeOp(127.5f, 127.5f))
            builder.build().also { cachedProcessor = it; cachedKey = key }
        }
        val image = TensorImage(if (isQuantized) DataType.UINT8 else DataType.FLOAT32)
        image.load(bitmap)
        return processor.process(image)
    }

    /** مورد ۱: استنتاج واقعی؛ فقط خودِ interpreter.run زمان‌سنجی می‌شود (نانوثانیه) */
    fun runInference(input: TensorImage): Long {
        output.buffer.rewind()
        input.buffer.rewind() // ورودی ممکن است چند بار پشت‌سرهم استفاده شود (حالت رقابت منابع)
        val start = System.nanoTime()
        interpreter.run(input.buffer, output.buffer)
        return System.nanoTime() - start
    }

    /** مورد ۳: برچسب و امتیاز بالاترین کلاس (با دی‌کوانتیزه کردن خروجی UINT8) */
    fun topPrediction(): Prediction {
        val raw = output.floatArray
        var best = 0
        for (i in raw.indices) if (raw[i] > raw[best]) best = i
        val score = if (outputTensor.dataType() == DataType.UINT8) {
            outScale * (raw[best] - outZeroPoint)
        } else raw[best]
        val label = labels.getOrNull(best) ?: "کلاس #$best"
        return Prediction(label, score)
    }

    override fun close() {
        interpreter.close()
        delegate?.close()
    }

    companion object {
        const val MODEL_FILE = "model.tflite"
        const val LABELS_FILE = "labels.txt"

        /**
         * مورد ۵: اگر سخت‌افزار پشتیبانی نشود، استثنا پرتاب می‌شود تا فراخواننده
         * به‌طور کنترل‌شده به CPU برگردد (به‌جای کرش یا گزارش غلط).
         */
        fun create(context: Context, accelerator: Accelerator): ImageClassifierEngine {
            val model = FileUtil.loadMappedFile(context, MODEL_FILE)
            val labels = try {
                FileUtil.loadLabels(context, LABELS_FILE)
            } catch (e: Exception) {
                emptyList()
            }

            val options = Interpreter.Options()
            var delegate: AutoCloseable? = null
            when (accelerator) {
                Accelerator.CPU -> options.setNumThreads(4) // XNNPACK به‌طور پیش‌فرض فعال است

                Accelerator.GPU -> {
                    val compat = CompatibilityList()
                    try {
                        if (!compat.isDelegateSupportedOnThisDevice) {
                            throw UnsupportedOperationException("GPU این گوشی در لیست سازگاری TFLite نیست")
                        }
                        val gpu = GpuDelegate(compat.bestOptionsForThisDevice)
                        delegate = gpu
                        options.addDelegate(gpu)
                    } finally {
                        compat.close()
                    }
                }

                Accelerator.NNAPI -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                        throw UnsupportedOperationException("NNAPI به اندروید ۸.۱ یا بالاتر نیاز دارد")
                    }
                    val nnapi = NnApiDelegate()
                    delegate = nnapi
                    options.addDelegate(nnapi)
                }
            }

            return try {
                // خطای واقعی ناسازگاری GPU/NNAPI معمولاً همین‌جا رخ می‌دهد، نه در سازنده‌ی Delegate
                ImageClassifierEngine(Interpreter(model, options), delegate, accelerator, labels)
            } catch (e: Throwable) {
                delegate?.close()
                throw e
            }
        }
    }
}
