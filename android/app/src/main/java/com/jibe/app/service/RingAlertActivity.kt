package com.jibe.app.service

import android.app.NotificationManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jibe.app.R
import com.jibe.app.ui.theme.JibeTheme

/**
 * Full-screen ring alert for ``device.ring`` from the daemon — plays the default ringtone at
 * maximum volume until dismissed. Designed to work over the lock screen.
 */
class RingAlertActivity : ComponentActivity() {

    companion object {
        const val RING_NOTIFICATION_ID = 9001
    }

    private var ringtone: Ringtone? = null
    private var audioManager: AudioManager? = null
    private var previousVolume: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager = am
        previousVolume = am.getStreamVolume(AudioManager.STREAM_RING)
        val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        am.setStreamVolume(AudioManager.STREAM_RING, maxVolume, 0)

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(RING_NOTIFICATION_ID)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(this, uri)?.also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.isLooping = true
            }
            it.play()
        }

        setContent {
            JibeTheme(isDark = true) {
                RingAlertContent(onStop = { finish() })
            }
        }
    }

    override fun onDestroy() {
        ringtone?.stop()
        ringtone = null
        if (previousVolume >= 0) {
            audioManager?.setStreamVolume(AudioManager.STREAM_RING, previousVolume, 0)
        }
        audioManager = null
        super.onDestroy()
    }
}

@Composable
private fun RingAlertContent(onStop: () -> Unit) {
    Column(
            modifier =
                    Modifier.fillMaxSize()
                            .background(Color(0xFF080808))
                            .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
                text = stringResource(R.string.ring_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
                text = stringResource(R.string.ring_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFB0B0B0),
                modifier = Modifier.padding(bottom = 48.dp)
        )
        Button(
                onClick = onStop,
                colors =
                        ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                        ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.width(200.dp).height(56.dp)
        ) {
            Text(text = stringResource(R.string.ring_stop), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(48.dp))
    }
}
