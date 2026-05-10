package com.jibe.app.service

import android.app.Notification
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.jibe.app.data.model.NotificationMessage
import java.io.ByteArrayOutputStream

/**
 * Mirrors posted notifications to the Linux daemon as ``notification`` protocol messages.
 *
 * Requires the user to enable notification access for Jibe in system settings.
 * Skips ongoing notifications (charging, foreground services, media controls) and
 * deduplicates repeat messages within a 30-second window.
 */
class JibeNotificationService : NotificationListenerService() {

    private val recentKeys = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        // Ongoing notifications are status-bar indicators (charging, media, foreground services),
        // not user-facing alerts — skip them to prevent spam.
        if (sbn.isOngoing) return

        val repo = JibeRepositoryHolder.connectionRepository ?: return

        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body =
                extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                        ?: ""

        if (title.isBlank() && body.isBlank()) return

        // Suppress identical (package, title, body) tuples within DEDUP_WINDOW_MS.
        val key = "${sbn.packageName}|${title}|${body}"
        val now = System.currentTimeMillis()
        synchronized(recentKeys) {
            val last = recentKeys[key]
            if (last != null && now - last < DEDUP_WINDOW_MS) return
            recentKeys[key] = now
            recentKeys.entries.removeIf { now - it.value > DEDUP_WINDOW_MS * 2 }
        }

        val appName = resolveAppLabel(sbn.packageName)
        val iconB64 = extractSmallIcon(sbn)
        val imageB64 = extractLargeIcon(sbn)

        repo.sendNotification(
                NotificationMessage(
                        app = sbn.packageName,
                        appName = appName,
                        title = title,
                        body = body,
                        timestamp = sbn.postTime / 1000L,
                        icon = iconB64,
                        image = imageB64
                )
        )
    }

    private fun resolveAppLabel(pkg: String): String =
            try {
                val pm = packageManager
                val info =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getApplicationInfo(
                                    pkg,
                                    PackageManager.ApplicationInfoFlags.of(0)
                            )
                        } else {
                            @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
                        }
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                pkg
            }

    private fun extractSmallIcon(sbn: StatusBarNotification): String? =
            try {
                val drawable = sbn.notification.smallIcon?.loadDrawable(this) ?: return null
                bitmapToBase64(drawableToBitmap(drawable, SIZE_ICON), MAX_ICON_BYTES)
            } catch (_: Exception) {
                null
            }

    private fun extractLargeIcon(sbn: StatusBarNotification): String? =
            try {
                val drawable = sbn.notification.getLargeIcon()?.loadDrawable(this) ?: return null
                bitmapToBase64(drawableToBitmap(drawable, SIZE_IMAGE), MAX_IMAGE_BYTES)
            } catch (_: Exception) {
                null
            }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    private fun bitmapToBase64(bmp: Bitmap, maxBytes: Int): String? {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 85, baos)
        val bytes = baos.toByteArray()
        if (bytes.size > maxBytes) return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    companion object {
        private const val SIZE_ICON = 48
        private const val SIZE_IMAGE = 128
        private const val MAX_ICON_BYTES = 32 * 1024
        private const val MAX_IMAGE_BYTES = 96 * 1024
        private const val DEDUP_WINDOW_MS = 30_000L
    }
}
