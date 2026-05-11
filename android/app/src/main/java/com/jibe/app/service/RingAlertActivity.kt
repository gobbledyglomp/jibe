package com.jibe.app.service

import android.app.NotificationManager
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
 * Full-screen overlay for "Find my phone" — shows a large "Stop" button.
 *
 * Audio is managed by [RingPlayer] and may already be playing before this
 * activity launches. Dismissing the activity stops the ring.
 */
class RingAlertActivity : ComponentActivity() {

    companion object {
        const val RING_NOTIFICATION_ID = 9001
    }

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

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(RING_NOTIFICATION_ID)

        if (!RingPlayer.isPlaying) {
            RingPlayer.start(this)
        }

        setContent {
            JibeTheme(isDark = true) {
                RingAlertContent(onStop = {
                    RingPlayer.stop()
                    finish()
                })
            }
        }
    }

    override fun onDestroy() {
        RingPlayer.stop()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(RING_NOTIFICATION_ID)
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
