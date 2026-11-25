// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED ||
            context.getSystemService(UserManager::class.java)?.isSystemUser != true ||
            !Hardware.preferences(context).getBoolean("restore", false)) return
        val pending = goAsync()
        Hardware.worker.execute {
            try { Hardware.restore(context) } finally { pending.finish() }
        }
    }
}
