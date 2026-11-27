// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.app.Dialog
import android.media.AudioManager
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
    private var monitor: MonitorPreferences? = null
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
                    link(Page.MONITOR, R.drawable.ic_cpu, R.string.monitor_summary)
                    link(Page.PRESETS, R.drawable.ic_kernel_manager, R.string.presets_summary)
                }
                group(R.string.maintenance).link(
                    Page.SPEAKER, R.drawable.ic_clear_speaker, R.string.speaker_summary
                )
                group(R.string.boot).addPreference(restorePreference())
            }
            Page.CORES -> {
                status(R.drawable.ic_cpu, R.string.loading, R.string.cpu_online_label)
                trialControls()
                coreGroup(R.string.efficiency_cluster, Hardware.cores.take(6))
                coreGroup(R.string.performance_cluster, Hardware.cores.drop(6))
            }
            Page.KERNEL -> {
                intro(R.string.kernel_intro)
                trialControls()
                policyGroup(R.string.efficiency_cluster, Hardware.policies.take(2))
                policyGroup(R.string.performance_cluster, Hardware.policies.drop(2))
                resetButton()
            }
            Page.GPU -> {
                status(R.drawable.ic_gpu_manager, R.string.loading, R.string.current_gpu)
                intro(R.string.opp_description)
                trialControls()
                policyGroup(R.string.gpu_limits, Hardware.gpu)
                resetButton()
            }
            Page.MONITOR -> monitor = MonitorPreferences(preferenceScreen)
            Page.PRESETS -> {
                intro(R.string.presets_description)
                trialControls()
                group(R.string.presets).apply {
                    for (preset in Preset.entries) addPreference(Preference(requireContext()).apply {
                        key = preset.name
                        setTitle(preset.title)
                        setSummary(preset.description)
                        isEnabled = false
                        setOnPreferenceClickListener {
                            previewPreset(preset)
                            true
                        }
                    })
                }
            }
            Page.SPEAKER -> {
                status(R.drawable.ic_clear_speaker, R.string.speaker_ready, R.string.speaker_volume_hint)
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
        } else if (page != Page.MONITOR) {
            tuning.state.observe(viewLifecycleOwner, ::showTuning)
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(page.title)
        if (page == Page.SPEAKER) requireActivity().volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun onResume() {
        super.onResume()
        if (page != Page.SPEAKER && page != Page.MONITOR) tuning.refresh()
        monitor?.start()
    }

    override fun onPause() {
        speaker?.stop()
        monitor?.stop()
        super.onPause()
    }

    override fun onStop() {
        if (page == Page.SPEAKER) requireActivity().volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        super.onStop()
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

    private fun PreferenceCategory.info(rowKey: String, titleId: Int) {
        addPreference(Preference(requireContext()).apply {
            key = rowKey
            isSelectable = false
            setTitle(titleId)
            setSummary(R.string.loading)
        })
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
        if (page == Page.KERNEL) group.info(clusterKey(controls.first().index), R.string.cluster_status)
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

    private fun trialControls() {
        group(R.string.trial_section).apply {
            addPreference(SwitchPreferenceCompat(requireContext()).apply {
                key = TRIAL_MODE
                isPersistent = false
                setTitle(R.string.trial_mode)
                setSummary(R.string.trial_description)
                setOnPreferenceChangeListener { _, enabled ->
                    tuning.setTrialMode(enabled == true)
                    false
                }
            })
            for ((buttonKey, keep) in listOf(TRIAL_KEEP to true, TRIAL_REVERT to false)) {
                addPreference(SectionButtonPreference(requireContext()).apply {
                    key = buttonKey
                    isVisible = false
                    setTitle(if (keep) R.string.trial_keep else R.string.trial_revert)
                    setOnClickListener { tuning.finishTrial(keep) }
                })
            }
        }
    }

    internal fun resetTuning() {
        if (resetControls.isNotEmpty()) tuning.reset(resetControls)
    }

    private fun previewPreset(preset: Preset) {
        val snapshot = tuning.state.value?.snapshot ?: return
        val plan = Hardware.presetPlan(preset, snapshot)
        if (plan.isEmpty() || childFragmentManager.isStateSaved ||
            childFragmentManager.findFragmentByTag(PRESET_PREVIEW) != null
        ) return
        val message = (Hardware.policies + Hardware.gpu).joinToString("\n") { control ->
            val name = if (control in Hardware.policies) getString(
                R.string.preset_cluster_control,
                getString(if (control.index == 0) R.string.efficiency_cluster else R.string.performance_cluster),
                getString(control.title),
            ) else getString(control.title)
            val target = plan[control]?.let { label(control, it, snapshot.gpuLevelsKHz) }
                ?: getString(R.string.preset_skipped)
            getString(R.string.preset_line, name, target)
        }
        PresetPreviewDialog.create(preset, plan, message)
            .showNow(childFragmentManager, PRESET_PREVIEW)
    }

    internal fun applyPreset(preset: Preset, arguments: Bundle) {
        val plan = (Hardware.policies + Hardware.gpu).mapNotNull { control ->
            arguments.getString(control.key)?.let { control to it }
        }.toMap()
        tuning.applyPreset(preset, plan)
    }

    private fun showTuning(state: TuningState) {
        val snapshot = state.snapshot
        val gpuLevels = snapshot?.gpuLevelsKHz.orEmpty()
        val trial = state.trial
        val writable = snapshot != null && !state.applying && trial == null
        findPreference<SwitchPreferenceCompat>(TRIAL_MODE)?.apply {
            isEnabled = writable
            isChecked = state.trialMode || trial != null
            summary = when {
                state.rollbackFailed -> getString(R.string.trial_revert_failed)
                trial == null -> getString(R.string.trial_description)
                else -> {
                    val control = trial.control
                    val isCore = control in Hardware.cores
                    val title = if (isCore) getString(control.title, control.index) else getString(control.title)
                    val choice = if (isCore) getString(if (trial.choice == "1") R.string.online else R.string.offline)
                        else label(control, trial.choice, gpuLevels)
                    getString(R.string.trial_active, title, choice)
                }
            }
        }
        findPreference<Preference>(TRIAL_KEEP)?.apply {
            isVisible = trial != null && !state.rollbackFailed
            isEnabled = !state.applying
        }
        findPreference<Preference>(TRIAL_REVERT)?.apply {
            isVisible = trial != null
            isEnabled = !state.applying
        }
        when (page) {
            Page.HOME, Page.CORES -> updateCpuStatus(snapshot, state.applying)
            Page.KERNEL -> {
                findPreference<IntroPreference>(INTRO)?.setSummary(when {
                    state.applying -> R.string.applying
                    snapshot == null -> R.string.loading
                    else -> R.string.kernel_intro
                })
                snapshot?.clusters?.forEach(::showCluster)
            }
            Page.GPU -> findPreference<StatusPreference>(STATUS)?.apply {
                val frequency = snapshot?.gpuKHz
                title = when {
                    frequency != null -> getString(R.string.mhz, formatMHz(frequency))
                    snapshot == null -> getString(R.string.loading)
                    else -> getString(R.string.unavailable)
                }
                setSummary(if (state.applying) R.string.applying else R.string.current_gpu)
                busy = snapshot == null || state.applying
            }
            Page.PRESETS -> {
                for (preset in Preset.entries) findPreference<Preference>(preset.name)?.apply {
                    val available = snapshot != null && Hardware.presetPlan(preset, snapshot).isNotEmpty()
                    isEnabled = writable && !state.trialMode && available
                    setSummary(when {
                        state.applying -> R.string.applying
                        state.trial != null || state.trialMode -> R.string.preset_trial_disabled
                        snapshot == null -> R.string.loading
                        !available -> R.string.preset_unavailable
                        else -> preset.description
                    })
                }
            }
            Page.MONITOR, Page.SPEAKER -> Unit
        }

        if (page == Page.CORES) {
            for (control in Hardware.cores) {
                val reading = snapshot?.controls?.get(control)
                findPreference<SwitchPreferenceCompat>(control.key)?.apply {
                    isEnabled = writable && reading?.value != null && reading.unavailable == null
                    isChecked = reading?.value == "1"
                    setSummary(when {
                        snapshot == null -> R.string.loading
                        reading?.unavailable != null -> reading.unavailable
                        state.applying -> R.string.applying
                        state.trial != null -> R.string.control_trial_running
                        reading?.value == "1" -> R.string.online
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
                entries = options.map { label(control, it, gpuLevels) }.toTypedArray()
                this.value = value?.value
                summary = when {
                    snapshot == null -> getString(R.string.loading)
                    value?.unavailable != null -> getString(value.unavailable)
                    value?.value == null -> getString(R.string.unavailable)
                    state.applying -> getString(R.string.applying)
                    state.trial != null -> getString(R.string.control_trial_running)
                    else -> label(control, value.value, gpuLevels)
                }
                isEnabled = writable && value?.value != null && value.unavailable == null
            }
        }
        findPreference<Preference>(RESET)?.isEnabled = writable && resetControls.any { control ->
            val value = snapshot?.controls?.get(control)
            value?.value != null && value.unavailable == null
        }
    }

    private fun showCluster(cluster: ClusterReading) {
        findPreference<Preference>(clusterKey(cluster.policy))?.summary =
            requireContext().clusterSummary(cluster)
    }

    private fun clusterKey(policy: Int) = "cluster_status_$policy"

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
            setSummary(if (cycle.active) R.string.speaker_duration else R.string.speaker_volume_hint)
            busy = cycle.active
        }
        findPreference<Preference>(SPEAKER_BUTTON)?.setTitle(
            if (cycle.active) R.string.speaker_stop else R.string.speaker_start
        )
    }

    private fun formatMHz(khz: Long) = NumberFormat.getNumberInstance().format(khz / 1000.0)

    private fun label(control: Control, value: String, gpuLevelsKHz: Map<Int, Long>) = when {
        control in Hardware.gpu -> gpuLevelsKHz[value.toIntOrNull()]?.let {
            getString(R.string.opp_mhz, formatMHz(it), value)
        } ?: getString(R.string.opp, value)
        control.node.name == "scaling_max_freq" -> getString(
            R.string.khz, value.toLongOrNull()?.let { NumberFormat.getIntegerInstance().format(it) } ?: value
        )
        else -> value
    }

    companion object {
        private const val STATUS = "status"
        private const val INTRO = "intro"
        private const val RESET = "reset"
        private const val TRIAL_MODE = "trial_mode"
        private const val TRIAL_KEEP = "trial_keep"
        private const val TRIAL_REVERT = "trial_revert"
        private const val SPEAKER_BUTTON = "speaker_button"
        private const val PRESET_PREVIEW = "preset_preview"

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

class PresetPreviewDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        val preset = Preset.valueOf(checkNotNull(arguments.getString(PRESET)))
        return AlertDialog.Builder(requireContext())
            .setTitle(preset.title)
            .setMessage(arguments.getString(MESSAGE))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.preset_apply) { _, _ ->
                (requireParentFragment() as PartsFragment).applyPreset(preset, arguments)
            }
            .create()
    }

    companion object {
        private const val PRESET = "preset"
        private const val MESSAGE = "message"

        internal fun create(preset: Preset, targets: Map<Control, String>, message: String) =
            PresetPreviewDialog().apply {
                arguments = Bundle().apply {
                    putString(PRESET, preset.name)
                    putString(MESSAGE, message)
                    for ((control, value) in targets) putString(control.key, value)
                }
            }
    }
}
