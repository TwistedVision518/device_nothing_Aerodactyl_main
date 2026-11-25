// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.collapsingtoolbar.R.id.content_frame
import com.android.settingslib.widget.SettingsBasePreferenceFragment

class PartsFragment : SettingsBasePreferenceFragment() {
    private val page get() = Page.valueOf(arguments?.getString("page") ?: Page.HOME.name)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var pending = false
    private val stopTone = Runnable { stopSpeaker() }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val context = requireContext()
        preferenceScreen.removeAll()
        preferenceScreen.isEnabled = !pending
        requireActivity().setTitle(when (page) {
            Page.HOME -> R.string.parts_name
            Page.CORES -> R.string.cpu
            Page.KERNEL -> R.string.kernel
            Page.GPU -> R.string.gpu
        })
        when (page) {
            Page.HOME -> {
                link(Page.CORES, R.string.cpu, R.drawable.ic_cpu)
                link(Page.KERNEL, R.string.kernel, R.drawable.ic_kernel_manager)
                link(Page.GPU, R.string.gpu, R.drawable.ic_gpu_manager)
                preferenceScreen.addPreference(Preference(context).apply {
                    key = "speaker"
                    setTitle(R.string.speaker)
                    setIcon(R.drawable.ic_clear_speaker)
                    setSummary(if (player == null) R.string.speaker_start else R.string.speaker_stop)
                    setOnPreferenceClickListener {
                        if (player == null) startSpeaker() else stopSpeaker()
                        true
                    }
                })
                val prefs = Hardware.preferences(context)
                preferenceScreen.addPreference(SwitchPreferenceCompat(context).apply {
                    isPersistent = false
                    setTitle(R.string.restore)
                    setSummary(R.string.restore_summary)
                    isChecked = prefs.getBoolean("restore", false)
                    setOnPreferenceChangeListener { _, enabled ->
                        prefs.edit().putBoolean("restore", enabled as Boolean).apply()
                        true
                    }
                })
            }
            Page.CORES -> Hardware.cores.forEach { control ->
                val current = Hardware.value(control)
                val anchor = control.index == 0 || control.index == 6
                preferenceScreen.addPreference(SwitchPreferenceCompat(context).apply {
                    isPersistent = false
                    title = getString(control.title, control.index)
                    isEnabled = !anchor && current in listOf("0", "1")
                    isChecked = current == "1" || (control.index == 0 && current == null)
                    if (anchor) setSummary(R.string.cluster_anchor)
                    else if (!isEnabled) setSummary(R.string.unavailable)
                    setOnPreferenceChangeListener { _, enabled ->
                        change(control, if (enabled == true) "1" else "0")
                        false
                    }
                })
            }
            Page.KERNEL -> {
                Hardware.policies.forEach(::selector)
                resetButton(Hardware.policies)
            }
            Page.GPU -> {
                preferenceScreen.addPreference(Preference(context).apply {
                    setSummary(R.string.opp_description)
                    isSelectable = false
                })
                Hardware.currentGpuKHz()?.let { frequency ->
                    preferenceScreen.addPreference(Preference(context).apply {
                        setTitle(R.string.current_gpu)
                        summary = getString(R.string.khz, frequency.toString())
                        isSelectable = false
                    })
                }
                Hardware.gpu.forEach(::selector)
                resetButton(Hardware.gpu)
            }
        }
    }

    private fun link(destination: Page, titleId: Int, iconId: Int) {
        preferenceScreen.addPreference(Preference(requireContext()).apply {
            setTitle(titleId)
            setIcon(iconId)
            setOnPreferenceClickListener {
                val detail = PartsFragment().apply {
                    arguments = Bundle().apply { putString("page", destination.name) }
                }
                parentFragmentManager.beginTransaction().replace(content_frame, detail)
                    .addToBackStack(destination.name).commit()
                true
            }
        })
    }

    private fun selector(control: Control) {
        val options = Hardware.choices(control)
        val current = Hardware.value(control)
        fun label(value: String) = when {
            control in Hardware.gpu -> getString(R.string.opp, value)
            control.node.name == "scaling_max_freq" -> getString(R.string.khz, value)
            else -> value
        }
        preferenceScreen.addPreference(ListPreference(requireContext()).apply {
            isPersistent = false
            title = getString(control.title, control.index)
            dialogTitle = title
            entries = options.map(::label).toTypedArray()
            entryValues = options.toTypedArray()
            value = current
            isEnabled = current != null && options.isNotEmpty()
            summary = if (isEnabled) label(current!!) else getString(R.string.unavailable)
            // AndroidX uses the key to reconnect ListPreferenceDialogFragment after rotation.
            key = control.key
            setOnPreferenceChangeListener { _, selected ->
                change(control, selected as String)
                false
            }
        })
    }

    private fun resetButton(controls: List<Control>) {
        val context = requireContext().applicationContext
        preferenceScreen.addPreference(Preference(requireContext()).apply {
            setTitle(R.string.reset)
            setOnPreferenceClickListener {
                updateHardware {
                    val prefs = Hardware.preferences(context)
                    var success = true
                    for (control in controls) {
                        val choices = Hardware.choices(control)
                        val target = when {
                            control == Hardware.floor -> choices.lastOrNull()
                            control == Hardware.ceiling -> choices.firstOrNull()
                            control.node.name == "scaling_governor" -> "schedutil"
                            else -> choices.mapNotNull(String::toLongOrNull).maxOrNull()?.toString()
                        }
                        if (target != null && Hardware.apply(control, target)) {
                            prefs.edit().remove(control.key).apply()
                        } else {
                            success = false
                        }
                    }
                    success
                }
                true
            }
        })
    }

    private fun change(control: Control, selected: String) {
        val context = requireContext().applicationContext
        updateHardware {
            val accepted = Hardware.apply(control, selected)
            if (accepted && control !in Hardware.cores) {
                Hardware.preferences(context).edit().putString(control.key, selected).apply()
            }
            accepted
        }
    }

    private fun updateHardware(operation: () -> Boolean) {
        if (pending) return
        val context = requireContext().applicationContext
        pending = true
        preferenceScreen.isEnabled = false
        Hardware.worker.execute {
            val accepted = operation()
            handler.post {
                pending = false
                if (!accepted) Toast.makeText(context, R.string.write_failed, Toast.LENGTH_SHORT).show()
                if (isResumed) render()
            }
        }
    }

    private fun startSpeaker() {
        val context = requireContext()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val speaker = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        try {
            val tone = MediaPlayer.create(context, R.raw.clear_speaker_sound)
                ?: throw IllegalStateException("Audio resource unavailable")
            player = tone
            check(speaker != null && tone.setPreferredDevice(speaker))
            tone.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { route ->
                val output = route.routedDevice
                if (player === tone && output != null && output.id != speaker.id) {
                    stopSpeaker()
                    Toast.makeText(context, R.string.speaker_failed, Toast.LENGTH_SHORT).show()
                }
            }, handler)
            tone.isLooping = true
            tone.setOnErrorListener { _, _, _ -> stopSpeaker(); true }
            tone.start()
            handler.postDelayed(stopTone, 30_000)
            findPreference<Preference>("speaker")?.setSummary(R.string.speaker_stop)
        } catch (_: RuntimeException) {
            stopSpeaker()
            Toast.makeText(context, R.string.speaker_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSpeaker() {
        handler.removeCallbacks(stopTone)
        player?.release()
        player = null
        findPreference<Preference>("speaker")?.setSummary(R.string.speaker_start)
    }

    override fun onStop() {
        stopSpeaker()
        super.onStop()
    }
}
