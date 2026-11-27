// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import java.io.File

internal data class ClusterReading(
    val policy: Int,
    val title: Int,
    val currentKHz: Long?,
    val minKHz: Long?,
    val maxKHz: Long?,
    val governor: String?,
    val online: Int?,
    val cores: Int,
)

internal data class CpuTicks(val busy: Long, val total: Long) {
    fun usageSince(previous: CpuTicks?): Float? {
        if (previous == null) return null
        val elapsed = total - previous.total
        val active = busy - previous.busy
        return if (elapsed > 0 && active in 0..elapsed) 100f * active / elapsed else null
    }
}

internal data class ActivityReading(
    val thermalStatus: Int?,
    val batteryTenths: Int?,
    val gpuThermalCap: Int?,
    val cpuCooling: Map<Int, Int?>,
    val clusters: List<ClusterReading>,
    val cpuTicks: CpuTicks?,
    val gpuUsage: Float?,
    val gpuKHz: Long?,
)

internal object Telemetry {
    private val ged = File("/sys/kernel/ged/hal")
    private val procStat = File("/proc/stat")
    private val gpuStatus = File("/proc/gpufreqv2/gpufreq_status")
    private val ppmCeiling = Regex("""\[PPM Ceiling]\s+LimitIndex:\s*(\d+),\s*Limiter:\s*(\d+),.*""")
    private val whitespace = Regex("\\s+")

    fun cpuCoolingDevices(): Map<Int, List<File>> =
        File("/sys/devices/virtual/thermal").listFiles().orEmpty()
            .filter { it.name.startsWith("cooling_device") }
            .mapNotNull { device ->
                val policy = when (Hardware.read(File(device, "type"))) {
                    "cpufreq-cpu0" -> 0
                    "cpufreq-cpu6" -> 6
                    else -> return@mapNotNull null
                }
                policy to File(device, "cur_state")
            }.groupBy({ it.first }, { it.second })

    fun clusters(values: Map<Control, String?>): List<ClusterReading> =
        Hardware.policies.chunked(2).map { (governor, maximum) ->
            val policy = governor.index
            val cores = if (policy == 0) Hardware.cores.take(6) else Hardware.cores.drop(6)
            val readings = cores.map { values[it] }
            val directory = governor.node.parentFile
            ClusterReading(
                policy,
                if (policy == 0) R.string.efficiency_cluster else R.string.performance_cluster,
                Hardware.read(File(directory, "scaling_cur_freq"))?.toLongOrNull()?.takeIf { it > 0 },
                Hardware.read(File(directory, "scaling_min_freq"))?.toLongOrNull()?.takeIf { it > 0 },
                values[maximum]?.toLongOrNull()?.takeIf { it > 0 },
                values[governor],
                readings.takeIf { values -> values.all { it == "0" || it == "1" } }
                    ?.count { it == "1" },
                cores.size,
            )
        }

    fun sample(context: Context, coolingDevices: Map<Int, List<File>>): ActivityReading {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }
        val power = context.getSystemService(PowerManager::class.java)
        val gpuCap = Hardware.read(gpuStatus)?.lineSequence()?.firstNotNullOfOrNull { line ->
            val match = ppmCeiling.matchEntire(line.trim()) ?: return@firstNotNullOfOrNull null
            val limiter = match.groupValues[2].toIntOrNull()
            val index = match.groupValues[1].toIntOrNull() ?: return@firstNotNullOfOrNull null
            // MediaTek gpufreq v2 currently selects THERMAL_AP (6) or THERMAL_EB (7).
            if (index > 0 && (limiter == 6 || limiter == 7)) index else NO_GPU_THERMAL_CAP
        }
        // The aggregate line is shared by all cores. Guest time is already included in user time.
        val fields = Hardware.read(procStat)?.lineSequence()?.firstOrNull()?.split(whitespace)
            ?.takeIf { it.firstOrNull() == "cpu" && it.size >= 9 }
            ?.drop(1)?.take(8)?.mapNotNull(String::toLongOrNull)
        val ticks = fields?.takeIf { it.size == 8 && it.all { tick -> tick >= 0 } }?.let {
            CpuTicks(it.sum() - it[3] - it[4], it.sum())
        }
        return ActivityReading(
            power?.currentThermalStatus,
            battery,
            gpuCap,
            coolingDevices.mapValues { (_, nodes) ->
                val states = nodes.map { Hardware.read(it)?.toIntOrNull()?.takeIf { state -> state >= 0 } }
                val maximum = states.filterNotNull().maxOrNull()
                maximum?.takeIf { it > 0 || states.none { state -> state == null } }
            },
            clusters((Hardware.cores + Hardware.policies).associateWith(Hardware::value)),
            ticks,
            Hardware.read(File(ged, "gpu_utilization"))?.split(whitespace)?.firstOrNull()
                ?.toFloatOrNull()?.takeIf { it in 0f..100f },
            Hardware.read(File(ged, "current_freqency"))?.split(whitespace)?.getOrNull(1)
                ?.toLongOrNull()?.takeIf { it > 0 },
        )
    }

    const val NO_GPU_THERMAL_CAP = -1
}
