// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.IOException

internal class SpeakerCycle(
    private val context: Context,
    private val onChanged: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var player: AudioTrack? = null
    private var routing: AudioRouting.OnRoutingChangedListener? = null
    private val stopAfterCycle = Runnable { stop() }
    private val routeTimeout = Runnable {
        Log.w("PacmanParts", "Speaker route was not reported before timeout")
        fail()
    }
    val active get() = player != null
    var playing = false
        private set

    fun start() {
        if (active) return
        try {
            val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
            val speaker = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            checkNotNull(speaker)

            // PCM avoids opening an APK file descriptor in the media player service.
            val pcm = context.resources.openRawResource(R.raw.clear_speaker_sound)
                .use { it.readBytes() }
            val tone = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(24_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()
            player = tone
            check(tone.write(pcm, 0, pcm.size) == pcm.size)
            check(tone.setLoopPoints(0, pcm.size / Short.SIZE_BYTES, -1) == AudioTrack.SUCCESS)
            // A preferred device is only a request. Wait for an actual route before unmuting.
            tone.setVolume(0f)
            check(tone.setPreferredDevice(speaker))
            val listener = AudioRouting.OnRoutingChangedListener {
                verifyRoute(tone, speaker.id)
            }
            routing = listener
            tone.addOnRoutingChangedListener(listener, main)
            tone.play()
            main.postDelayed(routeTimeout, 2_000)
            onChanged()
            verifyRoute(tone, speaker.id)
        } catch (e: IOException) {
            Log.w("PacmanParts", "Could not read the speaker tone", e)
            fail()
        } catch (e: RuntimeException) {
            Log.w("PacmanParts", "Could not start the speaker tone", e)
            fail()
        }
    }

    private fun verifyRoute(tone: AudioTrack, speakerId: Int) {
        if (player !== tone) return
        val outputs = tone.routedDevices
        when {
            outputs.isEmpty() -> if (playing) fail() else Unit
            outputs.any { it.id != speakerId } -> {
                Log.w("PacmanParts", "Cleaning tone routed away from the built-in speaker")
                fail()
            }
            !playing -> {
                main.removeCallbacks(routeTimeout)
                tone.setVolume(1f)
                playing = true
                val audio = context.getSystemService(AudioManager::class.java)
                if (audio?.isStreamMute(AudioManager.STREAM_MUSIC) == true) {
                    Toast.makeText(context, R.string.speaker_volume, Toast.LENGTH_SHORT).show()
                }
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
