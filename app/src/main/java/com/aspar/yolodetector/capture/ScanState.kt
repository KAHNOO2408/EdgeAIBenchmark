package com.aspar.yolodetector.capture

import com.aspar.yolodetector.ml.CocoClasses
import com.aspar.yolodetector.ml.DetectionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * تنظیماتی که هم Activity (حالت عکس) و هم سرویس اسکن صفحه می‌خوانند.
 * سرویس در هر فریم مقدار فعلی را می‌خواند؛ پس تغییر اسلایدر/سوئیچ‌ها فوری اعمال می‌شود.
 */
object ScanSettings {
    @Volatile var confThreshold = 0.35f
    @Volatile var vehiclesOnly = false
    @Volatile var useGpu = true

    fun classFilter(): Set<Int>? = if (vehiclesOnly) CocoClasses.VEHICLES else null
}

/** خروجی یک فریم از اسکن صفحه، برای نمایش آمار در Activity. */
data class ScanFrame(
    val result: DetectionResult,
    val fps: Float,
    val delegate: String,
    val inputWidth: Int,
    val inputHeight: Int,
)

/** کانال ارتباطی سرویس ← Activity. */
object ScanBus {
    enum class State { STOPPED, STARTING, RUNNING }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _frame = MutableStateFlow<ScanFrame?>(null)
    val frame: StateFlow<ScanFrame?> = _frame.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val isActive: Boolean get() = _state.value != State.STOPPED

    fun setState(s: State) {
        _state.value = s
        if (s != State.RUNNING) _frame.value = null
    }

    fun publishFrame(f: ScanFrame) {
        _frame.value = f
    }

    fun publishMessage(m: String?) {
        _message.value = m
    }
}
