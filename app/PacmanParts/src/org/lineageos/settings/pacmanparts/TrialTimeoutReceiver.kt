// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class TrialTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deadline = intent.getLongExtra(Hardware.TRIAL_DEADLINE, 0)
        val result = goAsync()
        Hardware.worker.execute {
            try {
                val trial = Hardware.pendingTrial(context)
                if (trial != null && trial.deadline == deadline && trial.expired &&
                    !Hardware.finishTrial(context, trial, keep = false)
                ) {
                    Log.w("PacmanParts", "Could not restore trial setting ${trial.control.key}")
                }
            } finally {
                result.finish()
            }
        }
    }
}
