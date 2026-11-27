// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.os.Bundle
import android.os.UserManager
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.collapsingtoolbar.R.id.content_frame

internal enum class Page(val title: Int) {
    HOME(R.string.parts_name),
    CORES(R.string.cpu),
    KERNEL(R.string.kernel),
    GPU(R.string.gpu),
    SPEAKER(R.string.speaker),
}

class PartsActivity : CollapsingToolbarBaseActivity() {
    private var openingPage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getSystemService(UserManager::class.java)?.isSystemUser != true) {
            finish()
            return
        }
        supportFragmentManager.addOnBackStackChangedListener { openingPage = false }
        if (savedInstanceState == null) {
            // The SettingsLib toolbar closes the activity when its fragment stack is empty.
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(content_frame, PartsFragment.forPage(Page.HOME))
                .addToBackStack(Page.HOME.name)
                .commit()
        }
    }

    internal fun openPage(destination: Page) {
        val fragments = supportFragmentManager
        if (openingPage || fragments.isStateSaved) return
        openingPage = true
        fragments.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(
                R.animator.parts_enter, R.animator.parts_exit,
                R.animator.parts_return, R.animator.parts_leave,
            )
            .replace(content_frame, PartsFragment.forPage(destination))
            .addToBackStack(destination.name)
            .commit()
    }
}
