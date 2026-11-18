package dev.phonememory

import android.app.KeyguardManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

class PowerGuard(private val context: Context) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val prefs = context.getSharedPreferences("power-policy", Context.MODE_PRIVATE)
    var threshold: Int
        get() = prefs.getInt("pause-at", 15)
        set(value) { require(value in 5..50); prefs.edit().putInt("pause-at", value).apply() }
    val percent get() = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: -1
    val charge get() = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it >= 0 } ?: -1L
    fun captureReason(): EndReason? = when {
        power.isPowerSaveMode -> EndReason.SAVER
        percent !in (threshold + 1)..100 -> EndReason.LOW_BATTERY
        else -> null
    }
    fun canProcess(chargingOnly: Boolean) = captureReason() == null &&
        power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE &&
        context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, true) == false &&
        context.getSystemService(android.os.UserManager::class.java).isUserUnlocked &&
        (if (chargingOnly) battery.isCharging && context.getSystemService(KeyguardManager::class.java).isDeviceSecure
        else !context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
}

class SileroGate(context: Context) : SpeechGate {
    private val vad = Vad(context.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
        model = "silero_vad.onnx", windowSize = 512), numThreads = 1, provider = "cpu"))
    // Pinned v5: one 512-sample hop plus 64 preceding samples at 16 kHz.
    private val window = FloatArray(576)
    private var primed = false
    override fun speech(frame: ShortArray, size: Int): Boolean {
        for (i in 0 until 512) window[64 + i] = if (i < size) frame[i] / 32768f else 0f
        val probability = vad.compute(window)
        window.copyInto(window, 0, 512, 576)
        primed = true
        return probability >= 0.5f
    }
    override fun quiet() {
        if (primed) { vad.reset(); window.fill(0f); primed = false }
    }
    override fun close() { window.fill(0f); vad.release() }
}
