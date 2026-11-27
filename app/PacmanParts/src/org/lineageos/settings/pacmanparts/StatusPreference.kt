// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.content.Context
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.settingslib.widget.GroupSectionDividerMixin

internal class StatusPreference(context: Context) : Preference(context), GroupSectionDividerMixin {
    var busy = false
        set(value) {
            if (field == value) return
            field = value
            notifyChanged()
        }

    init {
        isPersistent = false
        isSelectable = false
        layoutResource = R.layout.parts_status_card
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
        holder.findViewById(R.id.status_progress)?.visibility = if (busy) View.VISIBLE else View.GONE
    }
}
