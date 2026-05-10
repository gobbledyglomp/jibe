package com.jibe.app.data.repository

/** Applies plain-text clipboard updates from the daemon (typically on the main thread). */
fun interface ClipboardWriter {
    fun setPlainText(text: String)
}
