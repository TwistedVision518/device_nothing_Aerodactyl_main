// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

internal data class TuningState(
    val snapshot: HardwareSnapshot? = null,
    val applying: Boolean = false,
)

internal class TuningViewModel(private val app: Application) : AndroidViewModel(app) {
    private val main = Handler(Looper.getMainLooper())
    private val uiState = MutableLiveData(TuningState())
    val state: LiveData<TuningState> = uiState
    private var refreshing = false

    fun refresh() {
        if (refreshing || uiState.value?.applying == true) return
        refreshing = true
        Hardware.worker.execute {
            val snapshot = Hardware.snapshot()
            main.post {
                refreshing = false
                if (uiState.value?.applying != true) uiState.value = TuningState(snapshot)
            }
        }
    }

    fun change(control: Control, value: String) = apply(R.string.write_failed) {
        Hardware.change(app, control, value)
    }

    fun reset(controls: List<Control>) = apply(R.string.reset_failed) {
        Hardware.reset(app, controls)
    }

    private fun apply(error: Int, operation: () -> Boolean) {
        val current = uiState.value ?: return
        if (current.snapshot == null || current.applying) return
        uiState.value = current.copy(applying = true)
        Hardware.worker.execute {
            val accepted = try {
                operation()
            } catch (e: RuntimeException) {
                Log.w("PacmanParts", "Could not apply hardware settings", e)
                false
            }
            val snapshot = Hardware.snapshot()
            main.post {
                uiState.value = TuningState(snapshot)
                if (!accepted) Toast.makeText(app, error, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
