// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.os.SystemProperties
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class Control(val key: String, val node: File, val title: Int, val index: Int = 0)
internal data class ControlState(val value: String?, val choices: List<String>, val unavailable: Int?)
internal data class HardwareSnapshot(
    val controls: Map<Control, ControlState>,
    val gpuKHz: Long?,
    val gpuLevelsKHz: Map<Int, Long>,
    val gpuLevelCount: Int?,
    val clusters: List<ClusterReading>,
)
internal enum class Preset(val title: Int, val description: Int) {
    DEFAULT(R.string.preset_default, R.string.preset_default_description),
    BATTERY(R.string.preset_battery, R.string.preset_battery_description),
    PERFORMANCE(R.string.preset_performance, R.string.preset_performance_description),
}
internal data class Trial(
    val control: Control,
    val previous: String,
    val choice: String,
    val deadline: Long,
) {
    val expired get() = SystemClock.elapsedRealtime() >= deadline
}

// Only the controls listed here have matching vendor init rules. Reads are live;
// the preference store holds user selections, never an assumed hardware state.
internal object Hardware {
    val worker = Executors.newSingleThreadExecutor()
    private val cpu = File("/sys/devices/system/cpu")
    private val ged = File("/sys/kernel/ged/hal")
    private val gpuTable = File("/proc/gpufreqv2/gpu_working_opp_table")
    private val oppRow = Regex("""\[(\d+)]\s+freq:\s*(\d+),.*""")
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

    internal fun read(file: File): String? = try {
        file.readText().trim().ifEmpty { null }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    fun snapshot(): HardwareSnapshot {
        val values = controls.associateWith(::value)
        val gpuCount = gpuLevelCount()
        val clock = read(File(ged, "current_freqency"))?.split(Regex("\\s+"))
        val level = clock?.firstOrNull()?.toIntOrNull()
        val frequency = clock?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
        val states = values.mapValues { (control, current) ->
            val options = if (control in gpu) {
                gpuChoices(control, gpuCount, values[otherGpuLimit(control)])
            } else {
                choices(control)
            }
            val unavailable = when {
                control in cores && isManagedCore(control) -> R.string.cluster_anchor
                current == null -> readIssue(control.node)
                control in cores && current !in options -> R.string.control_read_failed
                control in gpu && gpuCount == null -> readIssue(
                    File(ged, "total_gpu_freq_level_count"), R.string.control_gpu_levels,
                )
                control in gpu && options.isEmpty() -> R.string.control_gpu_other_limit
                options.isEmpty() -> readIssue(checkNotNull(choicesNode(control)), R.string.control_no_choices)
                else -> null
            }
            ControlState(current, options, unavailable)
        }
        val table = if (gpuCount != null && level != null && frequency != null) {
            gpuLevels(gpuCount).takeIf { it[level] == frequency }.orEmpty()
        } else emptyMap()
        return HardwareSnapshot(states, frequency, table, gpuCount, Telemetry.clusters(values))
    }

    private fun readIssue(node: File, fallback: Int = R.string.control_read_failed): Int = try {
        if (Os.access(node.path, OsConstants.R_OK)) fallback else R.string.control_access_denied
    } catch (e: ErrnoException) {
        when (e.errno) {
            OsConstants.ENOENT, OsConstants.ENOTDIR -> R.string.control_missing
            OsConstants.EACCES, OsConstants.EPERM -> R.string.control_access_denied
            else -> fallback
        }
    }

    private fun gpuLevels(count: Int): Map<Int, Long> {
        val lines = read(gpuTable)?.lines() ?: return emptyMap()
        if (lines.size != count) return emptyMap()
        val levels = mutableMapOf<Int, Long>()
        var previous = Long.MAX_VALUE
        for ((index, line) in lines.withIndex()) {
            val row = oppRow.matchEntire(line.trim()) ?: return emptyMap()
            val khz = row.groupValues[2].toLongOrNull() ?: return emptyMap()
            if (row.groupValues[1].toIntOrNull() != index || khz <= 0 || khz > previous) {
                return emptyMap()
            }
            levels[index] = khz
            previous = khz
        }
        return levels
    }

    internal fun value(control: Control): String? = read(control.node)
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
        return choicesNode(control)?.let(::read)?.split(Regex("\\s+")).orEmpty()
    }

    private fun choicesNode(control: Control): File? = when (control.node.name) {
        "scaling_governor" -> File(control.node.parentFile, "scaling_available_governors")
        "scaling_max_freq" -> File(control.node.parentFile, "scaling_available_frequencies")
        else -> null
    }

    fun isManagedCore(control: Control) = control.index == 0 || control.index == 6

    // Run on the serial worker. init applies property triggers asynchronously.
    private fun apply(control: Control, choice: String, force: Boolean = false): Boolean {
        val current = value(control) ?: return false
        if (choice !in choices(control)) return false
        if (!force && current == choice) return true
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
        if (pendingTrial(context) != null) return false
        if (!apply(control, choice)) return false
        if (control !in cores) preferences(context).edit().putString(control.key, choice).apply()
        return true
    }

    const val TRIAL_CONTROL = "trial.control"
    const val TRIAL_DEADLINE = "trial.deadline"
    private const val TRIAL_PREVIOUS = "trial.previous"
    private const val TRIAL_CHOICE = "trial.choice"
    private const val TRIAL_BOOT = "trial.boot"

    private fun bootCount(context: Context) =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun SharedPreferences.Editor.clearTrial() =
        remove(TRIAL_CONTROL).remove(TRIAL_PREVIOUS).remove(TRIAL_CHOICE)
            .remove(TRIAL_BOOT).remove(TRIAL_DEADLINE)

    private fun timeoutIntent(context: Context, deadline: Long) =
        Intent(context, TrialTimeoutReceiver::class.java)
            .setIdentifier(deadline.toString()).putExtra(TRIAL_DEADLINE, deadline)

    private fun scheduledTimeout(context: Context, deadline: Long): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, timeoutIntent(context, deadline),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)

    // A force stop cancels pending intents. Recover any abandoned trial when the app reopens.
    fun needsRollback(context: Context, trial: Trial): Boolean =
        trial.expired || scheduledTimeout(context, trial.deadline) == null

    // Record the original setting before writing. Trials never change the saved boot values.
    fun pendingTrial(context: Context): Trial? {
        val prefs = preferences(context)
        val key = prefs.getString(TRIAL_CONTROL, null) ?: return null
        val boot = bootCount(context)
        if (boot >= 0 && prefs.getInt(TRIAL_BOOT, -1) != boot) {
            prefs.edit().clearTrial().apply()
            return null
        }
        val control = controls.firstOrNull { it.key == key } ?: return null
        val previous = prefs.getString(TRIAL_PREVIOUS, null) ?: return null
        val choice = prefs.getString(TRIAL_CHOICE, null) ?: return null
        val deadline = prefs.getLong(TRIAL_DEADLINE, 0)
        return Trial(control, previous, choice, deadline)
    }

    fun startTrial(context: Context, control: Control, choice: String): Boolean {
        if (pendingTrial(context) != null) return false
        val previous = value(control) ?: return false
        if (choice !in choices(control)) return false
        if (previous == choice) return true
        val boot = bootCount(context)
        if (boot < 0) return false
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        if (!alarms.canScheduleExactAlarms()) return false
        val trial = Trial(control, previous, choice, SystemClock.elapsedRealtime() + 120_000)
        val prefs = preferences(context)
        if (!prefs.edit().putString(TRIAL_CONTROL, control.key)
                .putString(TRIAL_PREVIOUS, previous).putString(TRIAL_CHOICE, choice)
                .putInt(TRIAL_BOOT, boot).putLong(TRIAL_DEADLINE, trial.deadline).commit()) return false
        try {
            val timeout = PendingIntent.getBroadcast(context, 0, timeoutIntent(context, trial.deadline),
                PendingIntent.FLAG_IMMUTABLE)
            alarms.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, trial.deadline, timeout,
            )
        } catch (e: SecurityException) {
            Log.w("PacmanParts", "Could not schedule trial rollback", e)
            prefs.edit().clearTrial().commit()
            return false
        }
        if (apply(control, choice)) return true
        finishTrial(context, trial, keep = false, forceRevert = true)
        return false
    }

    fun finishTrial(context: Context, trial: Trial, keep: Boolean, forceRevert: Boolean = false): Boolean {
        val pending = pendingTrial(context) ?: return true
        if (pending != trial) return false
        val saved = preferences(context).edit()
        if (keep) {
            if (trial.expired || value(trial.control) != trial.choice) return false
            if (trial.control !in cores) saved.putString(trial.control.key, trial.choice)
        } else if (!apply(trial.control, trial.previous, forceRevert)) {
            return false
        }
        if (!saved.clearTrial().commit()) return false
        scheduledTimeout(context, trial.deadline)?.let {
            context.getSystemService(AlarmManager::class.java)?.cancel(it)
            it.cancel()
        }
        return true
    }

    fun reset(context: Context, controls: List<Control>): Boolean {
        if (pendingTrial(context) != null) return false
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

    fun presetPlan(preset: Preset, snapshot: HardwareSnapshot): Map<Control, String> = buildMap {
        for (control in policies) {
            val options = snapshot.controls[control]?.takeIf { it.unavailable == null }?.choices.orEmpty()
            val target = if (control.node.name == "scaling_governor") {
                (if (preset == Preset.PERFORMANCE) "performance" else "schedutil").takeIf { it in options }
            } else {
                val frequencies = options.mapNotNull(String::toLongOrNull).filter { it > 0 }
                val max = frequencies.maxOrNull() ?: continue
                val min = snapshot.clusters.firstOrNull { it.policy == control.index }?.minKHz
                if (preset == Preset.BATTERY) {
                    if (min == null) continue
                    frequencies.filter { it >= min && it <= max * 4 / 5 }.maxOrNull()?.toString()
                } else max.toString()
            }
            if (target != null) put(control, target)
        }
        val count = snapshot.gpuLevelCount
        if (count != null) {
            val oldFloor = snapshot.controls[floor]?.value?.toIntOrNull()
            val oldCeiling = snapshot.controls[ceiling]?.value?.toIntOrNull()
            if (oldFloor != null && oldCeiling != null &&
                oldFloor in 0 until count && oldCeiling in 0..oldFloor
            ) {
                val cap = if (preset == Preset.BATTERY) {
                    val fastest = snapshot.gpuLevelsKHz[0]
                    if (fastest != null) snapshot.gpuLevelsKHz.entries
                        .firstOrNull { it.value <= fastest * 4 / 5 }?.key
                    else null
                } else 0
                if (cap != null) {
                    put(floor, (count - 1).toString())
                    put(ceiling, cap.toString())
                }
            }
        }
    }

    enum class PresetResult { APPLIED, STALE, FAILED, RECOVERY_FAILED }

    fun applyPreset(context: Context, preset: Preset, preview: Map<Control, String>): PresetResult {
        if (pendingTrial(context) != null || preview.isEmpty()) return PresetResult.STALE
        val latest = snapshot()
        val plan = presetPlan(preset, latest)
        if (plan != preview) return PresetResult.STALE
        val original = mutableMapOf<Control, String>()
        for (control in plan.keys) {
            original[control] = latest.controls[control]?.value ?: return PresetResult.STALE
        }
        val attempted = mutableListOf<Control>()
        // The preset widens the GPU floor first. Reverse order restores a valid pair.
        for ((control, target) in plan) {
            if (original[control] == target) continue
            attempted.add(control)
            if (!apply(control, target)) {
                var recovered = true
                for (changed in attempted.asReversed()) {
                    if (!apply(changed, original.getValue(changed), force = true)) recovered = false
                }
                return if (recovered) PresetResult.FAILED else PresetResult.RECOVERY_FAILED
            }
        }
        preferences(context).edit().apply {
            for ((control, target) in plan) {
                if (preset == Preset.DEFAULT) remove(control.key) else putString(control.key, target)
            }
        }.apply()
        return PresetResult.APPLIED
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
