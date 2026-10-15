package com.academic.edgebenchmark

import java.io.File
import java.util.Locale

/** مورد ۹: ثبت تک‌تک فریم‌ها و خلاصه‌ی آماری در فایل CSV برای رسم نمودار در گزارش */
class CsvRecorder {
    private val rows = StringBuilder()
    var count = 0
        private set

    fun reset() {
        rows.setLength(0)
        count = 0
    }

    fun add(
        accelerator: Accelerator,
        preprocessMs: Double,
        inferenceMs: Double,
        endToEndMs: Double,
        prediction: Prediction,
        system: SystemSnapshot,
    ) {
        count++
        rows.append(
            String.format(
                Locale.US,
                "%d,%d,%s,%.3f,%.3f,%.3f,\"%s\",%.4f,%.1f,%s,%.1f,%.1f\n",
                count,
                System.currentTimeMillis(),
                accelerator.name,
                preprocessMs,
                inferenceMs,
                endToEndMs,
                prediction.label.replace("\"", "'"),
                prediction.score,
                system.processCpuPercent,
                system.thermalStatus,
                system.batteryTempC,
                system.nativeHeapMb,
            )
        )
    }

    /** دو فایل می‌سازد: داده‌ی خام هر فریم و خلاصه‌ی آماری */
    fun save(
        dir: File,
        accelerator: Accelerator,
        modelDescription: String,
        inference: StatsSummary?,
        endToEnd: StatsSummary?,
        deviceInfo: String,
    ): File {
        val stamp = System.currentTimeMillis()
        val base = "benchmark_${accelerator.name}_$stamp"
        val raw = File(dir, "$base.csv")
        raw.writeText(
            "frame,timestamp_ms,accelerator,preprocess_ms,inference_ms,end_to_end_ms," +
                "top1_label,top1_score,process_cpu_pct,thermal_status,battery_temp_c,native_heap_mb\n" +
                rows
        )

        val summary = File(dir, "${base}_summary.csv")
        summary.writeText(buildString {
            append("metric,count,mean_ms,median_ms,p90_ms,min_ms,max_ms,std_ms\n")
            inference?.let { append(line("inference", it)) }
            endToEnd?.let { append(line("end_to_end", it)) }
            append("\n# device,\"$deviceInfo\"\n")
            append("# accelerator,${accelerator.name}\n")
            append("# model,\"$modelDescription\"\n")
        })
        return raw
    }

    private fun line(name: String, s: StatsSummary) = String.format(
        Locale.US, "%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f\n",
        name, s.count, s.mean, s.median, s.p90, s.min, s.max, s.stdDev,
    )
}
