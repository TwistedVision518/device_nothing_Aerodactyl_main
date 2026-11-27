// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.widget.Toast

internal class SpeakerCycle(
    private val context: Context,
    private val onChanged: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var routing: AudioRouting.OnRoutingChangedListener? = null
    private val stopAfterCycle = Runnable { stop() }
    private val routeTimeout = Runnable { fail() }
    val active get() = player != null
    var playing = false
        private set

    fun start() {
        if (active) return
        try {
            val audio = context.getSystemService(AudioManager::class.java)
            val speaker = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            checkNotNull(speaker)

            val tone = checkNotNull(MediaPlayer.create(context, R.raw.clear_speaker_sound))
            player = tone
            // A preferred device is only a request. Wait for an actual route before unmuting.
            tone.setVolume(0f, 0f)
            check(tone.setPreferredDevice(speaker))
            tone.isLooping = true
            tone.setOnErrorListener { _, _, _ ->
                if (player === tone) fail()
                true
            }
            val listener = AudioRouting.OnRoutingChangedListener {
                verifyRoute(tone, speaker.id)
            }
            routing = listener
            tone.addOnRoutingChangedListener(listener, main)
            tone.start()
            main.postDelayed(routeTimeout, 2_000)
            onChanged()
            verifyRoute(tone, speaker.id)
        } catch (_: RuntimeException) {
            fail()
        }
    }

    private fun verifyRoute(tone: MediaPlayer, speakerId: Int) {
        if (player !== tone) return
        val outputs = tone.routedDevices
        when {
            outputs.isEmpty() -> if (playing) fail() else Unit
            outputs.any { it.id != speakerId } -> fail()
            !playing -> {
                main.removeCallbacks(routeTimeout)
                tone.setVolume(1f, 1f)
                playing = true
                main.postDelayed(stopAfterCycle, 30_000)
                onChanged()
            }
        }
    }

    fun stop() {
        val tone = player ?: return
        player = null
        playing = false
        main.removeCallbacks(stopAfterCycle)
        main.removeCallbacks(routeTimeout)
        routing?.let(tone::removeOnRoutingChangedListener)
        routing = null
        tone.release()
        onChanged()
    }

    private fun fail() {
        stop()
        Toast.makeText(context, R.string.speaker_failed, Toast.LENGTH_SHORT).show()
    }
}
