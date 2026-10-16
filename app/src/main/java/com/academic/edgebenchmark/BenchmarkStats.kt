package com.academic.edgebenchmark

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

data class StatsSummary(
    val count: Int,
    val mean: Double,
    val median: Double,
    val p90: Double,
    val min: Double,
    val max: Double,
    val stdDev: Double,
)

/**
 * مورد ۷: آمار توصیفی زمان‌ها با حذف فریم‌های گرم‌کردن (warm-up).
 * چند فریم اول به‌خاطر کامپایل کرنل‌های GPU، تخصیص حافظه و کش سرد
 * کندتر از حالت پایدارند و نباید در میانگین بیایند.
 */
class BenchmarkStats(private val warmupFrames: Int = 10) {
    private val samples = ArrayList<Double>()
    private var seen = 0

    val isWarmingUp: Boolean get() = seen <= warmupFrames
    val count: Int get() = samples.size

    fun add(valueMs: Double) {
        seen++
        if (seen > warmupFrames) samples.add(valueMs)
    }

    fun reset() {
        samples.clear()
        seen = 0
    }

    fun summary(): StatsSummary? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val mean = sorted.average()
        val variance = sorted.sumOf { (it - mean) * (it - mean) } / sorted.size
        return StatsSummary(
            count = sorted.size,
            mean = mean,
            median = percentile(sorted, 0.50),
            p90 = percentile(sorted, 0.90),
            min = sorted.first(),
            max = sorted.last(),
            stdDev = sqrt(variance),
        )
    }

    /** صدک با درون‌یابی خطی بین دو نمونه‌ی مجاور */
    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}
