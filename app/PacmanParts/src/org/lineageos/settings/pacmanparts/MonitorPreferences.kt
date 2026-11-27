// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.IntroPreference
import java.io.File
import java.text.NumberFormat

internal class MonitorPreferences(screen: PreferenceScreen) {
    private val context = screen.context
    private val handler = Handler(Looper.getMainLooper())
    private var session = 0
    private var active = false
    private var previousCpu: CpuTicks? = null
    private val thermal = screen.category(R.string.thermal)
    private val thermalStatus = thermal.reading(R.string.thermal_status)
    private val batteryTemp = thermal.reading(R.string.battery_temperature)
    private val gpuThermal = thermal.reading(R.string.gpu_thermal)
    private val cpuThermal = thermal.reading(R.string.cpu_thermal)
    private val clusters = screen.category(R.string.cpu_clusters)
    private val clusterRows = mapOf(
        0 to clusters.reading(R.string.efficiency_cluster),
        6 to clusters.reading(R.string.performance_cluster),
    )
    private val history = screen.category(R.string.activity_history).apply {
        addPreference(IntroPreference(context).apply { setSummary(R.string.history_description) })
    }
    private val cpuUsage = history.chart(R.string.cpu_usage, 100f)
    private val gpuUsage = history.chart(R.string.gpu_usage, 100f)
    private val gpuClock = history.chart(R.string.gpu_clock)
    private val clusterClocks = mapOf(
        0 to history.chart(R.string.efficiency_clock),
        6 to history.chart(R.string.performance_clock),
    )

    fun start() {
        if (active) return
        active = true
        for (row in listOf(thermalStatus, batteryTemp, gpuThermal, cpuThermal) + clusterRows.values) {
            row.setSummary(R.string.loading)
        }
        for (chart in listOf(cpuUsage, gpuUsage, gpuClock) + clusterClocks.values) chart.clear()
        sample(session, null)
    }

    fun stop() {
        active = false
        session++
        handler.removeCallbacksAndMessages(null)
        previousCpu = null
    }

    private fun sample(currentSession: Int, discoveredCooling: Map<Int, List<File>>?) {
        val app = context.applicationContext
        Hardware.worker.execute {
            val cooling = discoveredCooling ?: Telemetry.cpuCoolingDevices()
            val value = Telemetry.sample(app, cooling)
            handler.post {
                if (!active || session != currentSession) return@post
                show(value)
                handler.postDelayed({ sample(currentSession, cooling) }, 1_000)
            }
        }
    }

    private fun show(value: ActivityReading) {
        thermalStatus.setSummary(when (value.thermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> R.string.thermal_none
            PowerManager.THERMAL_STATUS_LIGHT -> R.string.thermal_light
            PowerManager.THERMAL_STATUS_MODERATE -> R.string.thermal_moderate
            PowerManager.THERMAL_STATUS_SEVERE -> R.string.thermal_severe
            PowerManager.THERMAL_STATUS_CRITICAL -> R.string.thermal_critical
            PowerManager.THERMAL_STATUS_EMERGENCY -> R.string.thermal_emergency
            PowerManager.THERMAL_STATUS_SHUTDOWN -> R.string.thermal_shutdown
            else -> R.string.reading_unavailable
        })
        batteryTemp.summary = value.batteryTenths?.let {
            context.getString(R.string.temperature_c, NumberFormat.getNumberInstance().format(it / 10.0))
        } ?: context.getString(R.string.reading_unavailable)
        gpuThermal.summary = when (val cap = value.gpuThermalCap) {
            null -> context.getString(R.string.reading_unavailable)
            Telemetry.NO_GPU_THERMAL_CAP -> context.getString(R.string.gpu_thermal_inactive)
            else -> context.getString(R.string.gpu_thermal_active, cap)
        }
        cpuThermal.summary = value.clusters.joinToString("\n") { cluster ->
            val state = value.cpuCooling[cluster.policy]
            val reading = when {
                state == null -> context.getString(R.string.reading_unavailable)
                state == 0 -> context.getString(R.string.cpu_cooling_inactive)
                else -> context.getString(R.string.cpu_cooling_active, state)
            }
            context.getString(R.string.preset_line, context.getString(cluster.title), reading)
        }
        value.clusters.forEach { cluster ->
            clusterRows[cluster.policy]?.summary = context.clusterSummary(cluster)
            clusterClocks[cluster.policy]?.clock(cluster.currentKHz)
        }
        cpuUsage.usage(value.cpuTicks?.usageSince(previousCpu),
            measuring = value.cpuTicks != null && previousCpu == null)
        previousCpu = value.cpuTicks
        gpuUsage.usage(value.gpuUsage)
        gpuClock.clock(value.gpuKHz)
    }

    private fun PreferenceScreen.category(title: Int) = PreferenceCategory(context).also {
        it.setTitle(title)
        addPreference(it)
    }

    private fun PreferenceCategory.reading(title: Int) = Preference(context).also {
        it.setTitle(title)
        it.isSelectable = false
        addPreference(it)
    }

    private fun PreferenceCategory.chart(title: Int, max: Float? = null) =
        HistoryPreference(context, max).also {
            it.setTitle(title)
            addPreference(it)
        }

    private fun HistoryPreference.clock(khz: Long?) = record(khz?.toFloat(), context.frequencyLabel(khz))

    private fun HistoryPreference.usage(percent: Float?, measuring: Boolean = false) {
        val text = percent?.let { NumberFormat.getPercentInstance().format(it / 100.0) }
            ?: context.getString(if (measuring) R.string.measuring else R.string.reading_unavailable)
        record(percent, text)
    }
}

private fun Context.frequencyLabel(khz: Long?) = khz?.let {
    getString(R.string.mhz, NumberFormat.getNumberInstance().format(it / 1000.0))
} ?: getString(R.string.reading_unavailable)

internal fun Context.clusterSummary(cluster: ClusterReading): String {
    val online = cluster.online?.let { getString(R.string.core_count, it, cluster.cores) }
        ?: getString(R.string.reading_unavailable)
    return getString(
        R.string.cluster_details, frequencyLabel(cluster.currentKHz),
        frequencyLabel(cluster.minKHz), frequencyLabel(cluster.maxKHz),
        cluster.governor ?: getString(R.string.reading_unavailable), online,
    )
}
