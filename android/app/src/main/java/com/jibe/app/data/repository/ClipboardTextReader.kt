package com.jibe.app.data.repository

import android.content.ClipboardManager
import android.content.Context

object ClipboardTextReader {
    fun readPlainText(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    }
}
