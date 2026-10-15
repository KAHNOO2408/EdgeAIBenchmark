package com.academic.edgebenchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

data class SystemSnapshot(
    val processCpuPercent: Double,   // سهم این برنامه از کل ظرفیت همه‌ی هسته‌ها
    val thermalStatus: String,
    val batteryTempC: Double,
    val nativeHeapMb: Double,
)

/**
 * مورد ۱۱: سنجه‌هایی که با API عمومی اندروید واقعاً قابل اندازه‌گیری‌اند.
 * توجه: درصد مصرف GPU و NPU از طریق API عمومی اندروید در دسترس نیست؛
 * به‌جای ادعای غلط، اثر سخت‌افزار از طریق زمان استنتاج، مصرف CPU و دما سنجیده می‌شود.
 */
class SystemMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val cores = Runtime.getRuntime().availableProcessors()

    private var lastCpuMs = Process.getElapsedCpuTime()
    private var lastWallMs = SystemClock.elapsedRealtime()

    var latest = SystemSnapshot(0.0, "—", Double.NaN, 0.0)
        private set

    /** حداکثر یک بار در ثانیه نمونه می‌گیرد */
    fun sampleIfDue(): SystemSnapshot {
        val now = SystemClock.elapsedRealtime()
        val wallDelta = now - lastWallMs
        if (wallDelta < 1000) return latest

        val cpuNow = Process.getElapsedCpuTime()
        val cpuPercent = (cpuNow - lastCpuMs).toDouble() / (wallDelta * cores) * 100.0
        lastCpuMs = cpuNow
        lastWallMs = now

        latest = SystemSnapshot(
            processCpuPercent = cpuPercent.coerceIn(0.0, 100.0),
            thermalStatus = thermalStatus(),
            batteryTempC = batteryTemperature(),
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0),
        )
        return latest
    }

    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "نامشخص"
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "عادی"
            PowerManager.THERMAL_STATUS_LIGHT -> "گرم (سبک)"
            PowerManager.THERMAL_STATUS_MODERATE -> "گرم (متوسط)"
            PowerManager.THERMAL_STATUS_SEVERE -> "داغ (شدید)"
            PowerManager.THERMAL_STATUS_CRITICAL -> "بحرانی"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "اضطراری"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "خاموشی"
            else -> "نامشخص"
        }
    }

    private fun batteryTemperature(): Double {
        val intent: Intent? = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (tenths == Int.MIN_VALUE) Double.NaN else tenths / 10.0
    }
}
