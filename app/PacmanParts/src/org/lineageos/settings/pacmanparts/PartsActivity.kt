// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.os.Bundle
import android.os.UserManager
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.collapsingtoolbar.R.id.content_frame

class PartsActivity : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getSystemService(UserManager::class.java)?.isSystemUser != true) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            // The SettingsLib toolbar closes the activity when its fragment stack is empty.
            supportFragmentManager.beginTransaction().replace(content_frame, PartsFragment())
                .addToBackStack(Page.HOME.name).commit()
        }
    }
}
