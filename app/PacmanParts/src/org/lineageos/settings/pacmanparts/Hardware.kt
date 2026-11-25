// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.content.Context
import android.os.SystemProperties
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

internal enum class Page { HOME, CORES, KERNEL, GPU }
internal data class Control(val key: String, val node: File, val title: Int, val index: Int = 0)

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

    fun preferences(context: Context) = context.getSharedPreferences("tuning", Context.MODE_PRIVATE)

    private fun read(file: File): String? = try {
        file.readText().trim().ifEmpty { null }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    fun currentGpuKHz(): Long? = read(File(ged, "current_freqency"))
        ?.substringAfterLast(' ')?.toLongOrNull()?.takeIf { it > 0 }

    fun value(control: Control) = read(control.node)

    fun choices(control: Control): List<String> {
        if (control in gpu) {
            val count = read(File(ged, "total_gpu_freq_level_count"))?.toIntOrNull() ?: return emptyList()
            val other = value(if (control == floor) ceiling else floor)?.toIntOrNull() ?: return emptyList()
            if (count !in 1..1024 || other !in 0 until count) return emptyList()
            // GED index 0 is fastest. The floor index must be >= the ceiling index.
            return (if (control == floor) other until count else 0..other).map(Int::toString)
        }
        if (control in cores) return if (control.index in listOf(0, 6)) emptyList() else listOf("0", "1")
        val available = if (control.node.name == "scaling_governor") "scaling_available_governors"
                        else "scaling_available_frequencies"
        return read(File(control.node.parentFile, available))?.split(Regex("\\s+")).orEmpty()
    }

    // Run on the serial worker. init applies property triggers asynchronously.
    fun apply(control: Control, choice: String): Boolean {
        if (choice !in choices(control) || value(control) == null) return false
        if (value(control) == choice) return true
        try {
            SystemProperties.set("vendor.pacmanparts." + control.key, choice)
            repeat(20) {
                Thread.sleep(25)
                if (value(control) == choice) return true
            }
        } catch (e: RuntimeException) {
            Log.w("PacmanParts", "Cannot set ${control.key}", e)
        }
        return false
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
