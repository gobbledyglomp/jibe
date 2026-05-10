package com.jibe.app.service

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.jibe.app.R
import com.jibe.app.data.repository.ClipboardTextReader

/**
 * Transparent, no-UI activity used solely to bring the app to the foreground long enough for the
 * clipboard to be readable (Android 10+ blocks clipboard access from background contexts).
 *
 * Launched by the persistent notification's "Sync clipboard" action. Reads the primary clip,
 * pushes it to the daemon via [JibeRepositoryHolder], shows feedback with a toast, then finishes.
 */
class ClipboardSyncActivity : Activity() {

    private var syncStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        maybeStartClipboardSync()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            maybeStartClipboardSync()
        }
    }

    private fun maybeStartClipboardSync() {
        if (syncStarted) return
        syncStarted = true
        scheduleClipboardAttempt(0)
    }

    private fun scheduleClipboardAttempt(attempt: Int) {
        val delayMs = if (attempt == 0) FOREGROUND_SETTLE_DELAY_MS else RETRY_DELAY_MS
        Handler(Looper.getMainLooper()).postDelayed(
                { runClipboardSyncAttempt(attempt) },
                delayMs
        )
    }

    private fun runClipboardSyncAttempt(attempt: Int) {
        val repo = JibeRepositoryHolder.connectionRepository
        when {
            repo == null -> {
                toast(getString(R.string.clipboard_sync_not_ready))
                finish()
            }
            else -> {
                val text = ClipboardTextReader.readPlainText(this)
                when {
                    !text.isNullOrBlank() -> {
                        if (!repo.sendClipboardSync(text)) {
                            toast(getString(R.string.clipboard_sync_not_connected))
                        } else {
                            toast(getString(R.string.clipboard_sync_sent))
                        }
                        finish()
                    }
                    attempt < MAX_READ_ATTEMPTS - 1 -> scheduleClipboardAttempt(attempt + 1)
                    else -> {
                        toast(getString(R.string.clipboard_sync_empty))
                        finish()
                    }
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val FOREGROUND_SETTLE_DELAY_MS = 120L
        private const val RETRY_DELAY_MS = 120L
        private const val MAX_READ_ATTEMPTS = 4
    }
}
