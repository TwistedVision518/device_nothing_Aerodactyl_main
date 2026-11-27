// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.IntroPreference
import com.android.settingslib.widget.SectionButtonPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import java.text.NumberFormat

class PartsFragment : SettingsBasePreferenceFragment() {
    private val page get() = Page.valueOf(arguments?.getString("page") ?: Page.HOME.name)
    private val tuning by lazy { ViewModelProvider(requireActivity())[TuningViewModel::class.java] }
    private var speaker: SpeakerCycle? = null
    private val resetControls get() = when (page) {
        Page.KERNEL -> Hardware.policies
        Page.GPU -> Hardware.gpu
        else -> emptyList()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        when (page) {
            Page.HOME -> {
                status(R.drawable.ic_cpu, R.string.loading, R.string.cpu_online_label)
                group(R.string.performance).apply {
                    link(Page.CORES, R.drawable.ic_cpu, R.string.cpu_summary)
                    link(Page.KERNEL, R.drawable.ic_kernel_manager, R.string.kernel_summary)
                    link(Page.GPU, R.drawable.ic_gpu_manager, R.string.gpu_summary)
                }
                group(R.string.maintenance).link(
                    Page.SPEAKER, R.drawable.ic_clear_speaker, R.string.speaker_summary
                )
                group(R.string.boot).addPreference(restorePreference())
            }
            Page.CORES -> {
                status(R.drawable.ic_cpu, R.string.loading, R.string.cpu_online_label)
                coreGroup(R.string.efficiency_cluster, Hardware.cores.take(6))
                coreGroup(R.string.performance_cluster, Hardware.cores.drop(6))
            }
            Page.KERNEL -> {
                intro(R.string.kernel_intro)
                policyGroup(R.string.efficiency_cluster, Hardware.policies.take(2))
                policyGroup(R.string.performance_cluster, Hardware.policies.drop(2))
                resetButton()
            }
            Page.GPU -> {
                status(R.drawable.ic_gpu_manager, R.string.loading, R.string.current_gpu)
                intro(R.string.opp_description)
                policyGroup(R.string.gpu_limits, Hardware.gpu)
                resetButton()
            }
            Page.SPEAKER -> {
                status(R.drawable.ic_clear_speaker, R.string.speaker_ready, R.string.speaker_summary)
                preferenceScreen.addPreference(SectionButtonPreference(requireContext()).apply {
                    key = SPEAKER_BUTTON
                    setTitle(R.string.speaker_start)
                    setOnClickListener {
                        speaker?.let { cycle -> if (cycle.active) cycle.stop() else cycle.start() }
                    }
                })
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (page == Page.SPEAKER) {
            speaker = SpeakerCycle(requireContext().applicationContext, ::updateSpeaker)
        } else {
            tuning.state.observe(viewLifecycleOwner, ::showTuning)
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(page.title)
    }

    override fun onResume() {
        super.onResume()
        if (page != Page.SPEAKER) tuning.refresh()
    }

    override fun onPause() {
        speaker?.stop()
        super.onPause()
    }

    override fun onDestroyView() {
        speaker?.stop()
        speaker = null
        super.onDestroyView()
    }

    private fun status(icon: Int, titleId: Int, description: Int) {
        preferenceScreen.addPreference(StatusPreference(requireContext()).apply {
            key = STATUS
            setIcon(icon)
            setTitle(titleId)
            setSummary(description)
        })
    }

    private fun intro(description: Int) {
        preferenceScreen.addPreference(IntroPreference(requireContext()).apply {
            key = INTRO
            setSummary(description)
        })
    }

    private fun group(titleId: Int) = PreferenceCategory(requireContext()).apply {
        setTitle(titleId)
        preferenceScreen.addPreference(this)
    }

    private fun PreferenceCategory.link(destination: Page, iconId: Int, summaryId: Int) {
        addPreference(Preference(requireContext()).apply {
            setTitle(destination.title)
            setSummary(summaryId)
            setIcon(iconId)
            widgetLayoutResource = R.layout.parts_navigation_chevron
            setOnPreferenceClickListener {
                (requireActivity() as PartsActivity).openPage(destination)
                true
            }
        })
    }

    private fun restorePreference(): Preference {
        val prefs = Hardware.preferences(requireContext())
        return SwitchPreferenceCompat(requireContext()).apply {
            key = "restore"
            isPersistent = false
            setTitle(R.string.restore)
            setSummary(R.string.restore_summary)
            isChecked = prefs.getBoolean(key, false)
            setOnPreferenceChangeListener { _, enabled ->
                prefs.edit().putBoolean(key, enabled as Boolean).apply()
                true
            }
        }
    }

    private fun coreGroup(titleId: Int, controls: List<Control>) {
        val group = group(titleId)
        for (control in controls) {
            group.addPreference(SwitchPreferenceCompat(requireContext()).apply {
                key = control.key
                isPersistent = false
                title = getString(control.title, control.index)
                isEnabled = false
                setOnPreferenceChangeListener { _, enabled ->
                    tuning.change(control, if (enabled == true) "1" else "0")
                    false
                }
            })
        }
    }

    private fun policyGroup(titleId: Int, controls: List<Control>) {
        val group = group(titleId)
        for (control in controls) {
            group.addPreference(ListPreference(requireContext()).apply {
                key = control.key
                isPersistent = false
                setTitle(control.title)
                dialogTitle = title
                isEnabled = false
                setOnPreferenceChangeListener { _, selected ->
                    tuning.change(control, selected as String)
                    false
                }
            })
        }
    }

    private fun resetButton() {
        preferenceScreen.addPreference(SectionButtonPreference(requireContext()).apply {
            key = RESET
            isEnabled = false
            setTitle(R.string.reset)
            setOnClickListener {
                if (!childFragmentManager.isStateSaved &&
                    childFragmentManager.findFragmentByTag(RESET) == null
                ) {
                    ResetTuningDialog().showNow(childFragmentManager, RESET)
                }
            }
        })
    }

    internal fun resetTuning() {
        if (resetControls.isNotEmpty()) tuning.reset(resetControls)
    }

    private fun showTuning(state: TuningState) {
        val snapshot = state.snapshot
        val writable = snapshot != null && !state.applying
        when (page) {
            Page.HOME, Page.CORES -> updateCpuStatus(snapshot, state.applying)
            Page.KERNEL -> findPreference<IntroPreference>(INTRO)?.setSummary(
                when {
                    state.applying -> R.string.applying
                    snapshot == null -> R.string.loading
                    else -> R.string.kernel_intro
                }
            )
            Page.GPU -> findPreference<StatusPreference>(STATUS)?.apply {
                val frequency = snapshot?.gpuKHz
                title = when {
                    frequency != null -> getString(R.string.khz, NumberFormat.getIntegerInstance().format(frequency))
                    snapshot == null -> getString(R.string.loading)
                    else -> getString(R.string.unavailable)
                }
                setSummary(if (state.applying) R.string.applying else R.string.current_gpu)
                busy = snapshot == null || state.applying
            }
            Page.SPEAKER -> Unit
        }

        if (page == Page.CORES) {
            for (control in Hardware.cores) {
                val current = snapshot?.controls?.get(control)?.value
                val known = current == "0" || current == "1"
                findPreference<SwitchPreferenceCompat>(control.key)?.apply {
                    val managed = Hardware.isManagedCore(control)
                    isEnabled = writable && known && !managed
                    isChecked = current == "1"
                    setSummary(when {
                        snapshot == null -> R.string.loading
                        !known -> R.string.unavailable
                        managed -> R.string.cluster_anchor
                        isChecked -> R.string.online
                        else -> R.string.offline
                    })
                }
            }
        }
        for (control in resetControls) {
            val value = snapshot?.controls?.get(control)
            findPreference<ListPreference>(control.key)?.apply {
                val options = value?.choices.orEmpty()
                entryValues = options.toTypedArray()
                entries = options.map { label(control, it) }.toTypedArray()
                this.value = value?.value
                summary = when {
                    snapshot == null -> getString(R.string.loading)
                    value?.value == null -> getString(R.string.unavailable)
                    else -> label(control, value.value)
                }
                isEnabled = writable && value?.value != null && options.isNotEmpty()
            }
        }
        findPreference<Preference>(RESET)?.isEnabled = writable && resetControls.any { control ->
            val value = snapshot?.controls?.get(control)
            value?.value != null && value.choices.isNotEmpty()
        }
    }

    private fun updateCpuStatus(snapshot: HardwareSnapshot?, applying: Boolean) {
        findPreference<StatusPreference>(STATUS)?.apply {
            val readings = snapshot?.let { state -> Hardware.cores.map { state.controls[it]?.value } }
            title = when {
                readings == null -> getString(R.string.loading)
                readings.any { it != "0" && it != "1" } -> getString(R.string.unavailable)
                else -> getString(R.string.core_count, readings.count { it == "1" }, readings.size)
            }
            setSummary(if (applying) R.string.applying else R.string.cpu_online_label)
            busy = snapshot == null || applying
        }
    }

    private fun updateSpeaker() {
        val cycle = speaker ?: return
        findPreference<StatusPreference>(STATUS)?.apply {
            setTitle(when {
                cycle.playing -> R.string.speaker_playing
                cycle.active -> R.string.speaker_connecting
                else -> R.string.speaker_ready
            })
            setSummary(if (cycle.active) R.string.speaker_duration else R.string.speaker_summary)
            busy = cycle.active
        }
        findPreference<Preference>(SPEAKER_BUTTON)?.setTitle(
            if (cycle.active) R.string.speaker_stop else R.string.speaker_start
        )
    }

    private fun label(control: Control, value: String) = when {
        control in Hardware.gpu -> getString(R.string.opp, value)
        control.node.name == "scaling_max_freq" -> getString(
            R.string.khz, value.toLongOrNull()?.let { NumberFormat.getIntegerInstance().format(it) } ?: value
        )
        else -> value
    }

    companion object {
        private const val STATUS = "status"
        private const val INTRO = "intro"
        private const val RESET = "reset"
        private const val SPEAKER_BUTTON = "speaker_button"

        internal fun forPage(page: Page) = PartsFragment().apply {
            arguments = Bundle().apply { putString("page", page.name) }
        }
    }
}

class ResetTuningDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset)
            .setMessage(R.string.reset_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.reset_confirm) { _, _ ->
                (requireParentFragment() as PartsFragment).resetTuning()
            }
            .create()
}
