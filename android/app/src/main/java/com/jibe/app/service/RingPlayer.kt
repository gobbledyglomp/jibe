package com.jibe.app.service

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build

/**
 * Singleton that manages ringtone playback for "Find my phone."
 *
 * Audio starts immediately from whichever context triggers the ring (typically
 * the foreground service), independent of whether [RingAlertActivity] can
 * launch. The activity merely presents the "Stop" button and calls [stop].
 */
object RingPlayer {

    private var ringtone: Ringtone? = null
    private var audioManager: AudioManager? = null
    private var previousVolume: Int = -1

    val isPlaying: Boolean
        get() = ringtone?.isPlaying == true

    @Synchronized
    fun start(context: Context) {
        if (isPlaying) return

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        previousVolume = am.getStreamVolume(AudioManager.STREAM_RING)
        am.setStreamVolume(
            AudioManager.STREAM_RING,
            am.getStreamMaxVolume(AudioManager.STREAM_RING),
            0,
        )

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)?.also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.isLooping = true
            }
            it.play()
        }
    }

    @Synchronized
    fun stop() {
        ringtone?.stop()
        ringtone = null
        if (previousVolume >= 0) {
            audioManager?.setStreamVolume(AudioManager.STREAM_RING, previousVolume, 0)
        }
        previousVolume = -1
        audioManager = null
    }
}
