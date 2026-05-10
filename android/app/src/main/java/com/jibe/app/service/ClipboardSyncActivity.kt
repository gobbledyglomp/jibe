package com.jibe.app.service

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.jibe.app.R

/**
 * Transparent, no-UI activity used solely to bring the app to the foreground long enough for the
 * clipboard to be readable (Android 10+ blocks clipboard access from background contexts).
 *
 * Launched by the persistent notification's "Sync clipboard" action. Reads the primary clip,
 * pushes it to the daemon via [JibeRepositoryHolder], shows feedback with a toast, then finishes.
 */
class ClipboardSyncActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // Defer until the window is fully foreground — avoids empty reads on some OEM builds.
        Handler(Looper.getMainLooper()).post {
            runClipboardSyncAndFinish()
        }
    }

    private fun runClipboardSyncAndFinish() {
        val repo = JibeRepositoryHolder.connectionRepository
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()

        when {
            repo == null -> toast(getString(R.string.clipboard_sync_not_ready))
            text.isNullOrBlank() -> toast(getString(R.string.clipboard_sync_empty))
            !repo.sendClipboardSync(text) ->
                    toast(getString(R.string.clipboard_sync_not_connected))
            else -> toast(getString(R.string.clipboard_sync_sent))
        }
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }
}
