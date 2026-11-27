// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

internal data class TuningState(
    val snapshot: HardwareSnapshot? = null,
    val applying: Boolean = false,
    val trialMode: Boolean = false,
    val trial: Trial? = null,
    val rollbackFailed: Boolean = false,
)

internal class TuningViewModel(private val app: Application) : AndroidViewModel(app) {
    private val main = Handler(Looper.getMainLooper())
    private val uiState = MutableLiveData(TuningState())
    val state: LiveData<TuningState> = uiState
    private var refreshing = false
    private val updateAtDeadline = Runnable { refresh() }
    private val preferences = Hardware.preferences(app)
    private val trialChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Hardware.TRIAL_CONTROL) refresh()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(trialChanged)
    }

    override fun onCleared() {
        preferences.unregisterOnSharedPreferenceChangeListener(trialChanged)
        main.removeCallbacks(updateAtDeadline)
    }

    fun refresh() {
        if (refreshing || uiState.value?.applying == true) return
        refreshing = true
        Hardware.worker.execute {
            val pending = Hardware.pendingTrial(app)
            val recoveryFailed = pending != null && Hardware.needsRollback(app, pending) &&
                !Hardware.finishTrial(app, pending, keep = false)
            val trial = Hardware.pendingTrial(app)
            val snapshot = Hardware.snapshot()
            main.post {
                refreshing = false
                val current = uiState.value ?: return@post
                if (!current.applying) publish(current.copy(
                    snapshot = snapshot, trial = trial,
                    rollbackFailed = recoveryFailed || (trial != null && current.rollbackFailed),
                ))
            }
        }
    }

    fun setTrialMode(enabled: Boolean) {
        val current = uiState.value ?: return
        if (!current.applying && current.trial == null) {
            uiState.value = current.copy(trialMode = enabled)
        }
    }

    fun change(control: Control, value: String) {
        val current = uiState.value ?: return
        if (current.trial != null) return
        apply(if (current.trialMode) R.string.trial_start_failed else R.string.write_failed,
            startingTrial = current.trialMode) {
            if (current.trialMode) Hardware.startTrial(app, control, value)
            else Hardware.change(app, control, value)
        }
    }

    fun reset(controls: List<Control>) {
        if (uiState.value?.trial != null) return
        apply(R.string.reset_failed) { Hardware.reset(app, controls) }
    }

    fun applyPreset(preset: Preset, preview: Map<Control, String>) {
        val current = uiState.value ?: return
        if (current.trialMode || current.trial != null) return
        perform(R.string.preset_failed) {
            when (Hardware.applyPreset(app, preset, preview)) {
                Hardware.PresetResult.APPLIED -> null
                Hardware.PresetResult.STALE -> R.string.preset_stale
                Hardware.PresetResult.FAILED -> R.string.preset_reverted
                Hardware.PresetResult.RECOVERY_FAILED -> R.string.preset_recovery_failed
            }
        }
    }

    fun finishTrial(keep: Boolean) {
        val current = uiState.value ?: return
        val trial = current.trial ?: return
        if (current.applying || (keep && (current.rollbackFailed || trial.expired))) return
        apply(if (keep) R.string.trial_keep_failed else R.string.trial_revert_failed,
            revertingTrial = !keep) {
            Hardware.finishTrial(app, trial, keep)
        }
    }

    private fun apply(
        error: Int,
        startingTrial: Boolean = false,
        revertingTrial: Boolean = false,
        operation: () -> Boolean,
    ) = perform(error, startingTrial, revertingTrial) {
        if (operation()) null else error
    }

    private fun perform(
        error: Int,
        startingTrial: Boolean = false,
        revertingTrial: Boolean = false,
        operation: () -> Int?,
    ) {
        val current = uiState.value ?: return
        if (current.snapshot == null || current.applying) return
        uiState.value = current.copy(applying = true)
        Hardware.worker.execute {
            val failure = try {
                operation()
            } catch (e: RuntimeException) {
                Log.w("PacmanParts", "Could not apply hardware settings", e)
                error
            }
            val snapshot = Hardware.snapshot()
            val trial = Hardware.pendingTrial(app)
            main.post {
                val rollbackFailed = trial != null &&
                    (current.rollbackFailed || (failure != null && (startingTrial || revertingTrial)))
                publish(current.copy(
                    snapshot = snapshot, applying = false, trial = trial,
                    rollbackFailed = rollbackFailed,
                ))
                if (failure != null) Toast.makeText(app, failure, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun publish(current: TuningState) {
        uiState.value = current
        main.removeCallbacks(updateAtDeadline)
        val remaining = current.trial?.deadline?.minus(SystemClock.elapsedRealtime()) ?: return
        if (remaining > 0) main.postDelayed(updateAtDeadline, remaining)
    }
}
