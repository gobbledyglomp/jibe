package com.jibe.app.service

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle

/**
 * Transparent, no-UI activity used solely to bring the app to the foreground long enough for the
 * clipboard to be readable (Android 10+ blocks clipboard access from background contexts).
 *
 * Launched by the persistent notification's "Sync clipboard" action. Reads the primary clip,
 * pushes it to the daemon via [JibeRepositoryHolder], then finishes immediately.
 */
class ClipboardSyncActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        val repo = JibeRepositoryHolder.connectionRepository
        if (repo != null) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!text.isNullOrBlank()) {
                repo.sendClipboardSync(text)
            }
        }
        finish()
    }
}
