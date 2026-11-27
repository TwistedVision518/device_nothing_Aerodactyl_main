// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.content.Context
import android.os.SystemProperties
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class Control(val key: String, val node: File, val title: Int, val index: Int = 0)
internal data class ControlState(val value: String?, val choices: List<String>)
internal data class HardwareSnapshot(
    val controls: Map<Control, ControlState>,
    val gpuKHz: Long?,
)

// Only the controls listed here have matching vendor init rules. Reads are live;
// the preference store holds user selections, never an assumed hardware state.
internal object Hardware {
    val worker = Executors.newSingleThreadExecutor()
    private val cpu = File("/sys/devices/system/cpu")
    private val ged = File("/sys/kernel/ged/hal")
    val cores = (0..7).map {
        Control("cpu$it", File(cpu, "cpu$it/online"), R.string.core, it)
    }
    val policies = listOf(0, 6).flatMap {
        val dir = File(cpu, "cpufreq/policy$it")
        listOf(Control("policy$it.gov", File(dir, "scaling_governor"), R.string.governor, it),
               Control("policy$it.max", File(dir, "scaling_max_freq"), R.string.max_freq, it))
    }
    val floor = Control("gpu.floor", File(ged, "custom_boost_gpu_freq"), R.string.gpu_floor)
    val ceiling = Control("gpu.ceiling", File(ged, "custom_upbound_gpu_freq"), R.string.gpu_ceiling)
    val gpu = listOf(floor, ceiling)
    private val controls = cores + policies + gpu

    fun preferences(context: Context) = context.getSharedPreferences("tuning", Context.MODE_PRIVATE)

    private fun read(file: File): String? = try {
        file.readText().trim().ifEmpty { null }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    fun snapshot(): HardwareSnapshot {
        val values = controls.associateWith(::value)
        val gpuCount = gpuLevelCount()
        val frequency = read(File(ged, "current_freqency"))
            ?.split(Regex("\\s+"))?.lastOrNull()?.toLongOrNull()?.takeIf { it > 0 }
        val states = values.mapValues { (control, current) ->
            val options = if (control in gpu) {
                gpuChoices(control, gpuCount, values[otherGpuLimit(control)])
            } else {
                choices(control)
            }
            ControlState(current, options)
        }
        return HardwareSnapshot(states, frequency)
    }

    private fun value(control: Control): String? = read(control.node)
        ?: if (control == cores.first()) "1" else null

    private fun gpuLevelCount() = read(File(ged, "total_gpu_freq_level_count"))
        ?.toIntOrNull()?.takeIf { it in 1..1024 }

    private fun otherGpuLimit(control: Control) = if (control == floor) ceiling else floor

    private fun gpuChoices(control: Control, count: Int?, otherValue: String?): List<String> {
        if (count == null) return emptyList()
        val other = otherValue?.toIntOrNull() ?: return emptyList()
        if (other !in 0 until count) return emptyList()
        // GED index 0 is fastest. The floor index must be >= the ceiling index.
        return (if (control == floor) other until count else 0..other).map(Int::toString)
    }

    private fun choices(control: Control): List<String> {
        if (control in gpu) return gpuChoices(control, gpuLevelCount(), value(otherGpuLimit(control)))
        if (control in cores) return if (isManagedCore(control)) emptyList() else listOf("0", "1")
        val available = if (control.node.name == "scaling_governor") "scaling_available_governors"
                        else "scaling_available_frequencies"
        return read(File(control.node.parentFile, available))?.split(Regex("\\s+")).orEmpty()
    }

    fun isManagedCore(control: Control) = control.index == 0 || control.index == 6

    // Run on the serial worker. init applies property triggers asynchronously.
    private fun apply(control: Control, choice: String): Boolean {
        val current = value(control) ?: return false
        if (choice !in choices(control)) return false
        if (current == choice) return true
        try {
            SystemProperties.set("vendor.pacmanparts." + control.key, choice)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
            do {
                if (value(control) == choice) return true
                Thread.sleep(25)
            } while (System.nanoTime() < deadline)
            val readback = value(control)
            if (readback == choice) return true
            Log.w("PacmanParts", "${control.key}: requested $choice, read back $readback")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: RuntimeException) {
            Log.w("PacmanParts", "Cannot set ${control.key}", e)
        }
        return false
    }

    fun change(context: Context, control: Control, choice: String): Boolean {
        if (!apply(control, choice)) return false
        if (control !in cores) preferences(context).edit().putString(control.key, choice).apply()
        return true
    }

    fun reset(context: Context, controls: List<Control>): Boolean {
        val saved = preferences(context).edit()
        var success = true
        for (control in controls) {
            val options = choices(control)
            val target = when (control) {
                floor -> options.lastOrNull()
                ceiling -> options.firstOrNull()
                else -> if (control.node.name == "scaling_governor") "schedutil"
                        else options.mapNotNull(String::toLongOrNull).maxOrNull()?.toString()
            }
            if (target != null && apply(control, target)) {
                saved.remove(control.key)
            } else {
                success = false
            }
        }
        saved.apply()
        return success
    }

    fun restore(context: Context) {
        val prefs = preferences(context)
        if (!prefs.getBoolean("restore", false)) return
        // Widen the current GPU range first if the saved floor would cross the current ceiling.
        val savedFloor = prefs.getString(floor.key, null)?.toIntOrNull()
        val currentCeiling = value(ceiling)?.toIntOrNull()
        val ordered = if (savedFloor != null && currentCeiling != null && savedFloor < currentCeiling)
            listOf(ceiling, floor) else gpu
        for (control in policies + ordered) {
            val wanted = prefs.getString(control.key, null) ?: continue
            if (!apply(control, wanted)) Log.w("PacmanParts", "Could not restore ${control.key}")
        }
    }
}
