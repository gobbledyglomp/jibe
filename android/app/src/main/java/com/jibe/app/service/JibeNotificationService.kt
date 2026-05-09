package com.jibe.app.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jibe.app.data.model.NotificationMessage

/**
 * Mirrors posted notifications to the Linux daemon as ``notification`` protocol messages.
 *
 * Requires the user to enable notification access for Jibe in system settings.
 */
class JibeNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        val repo = JibeRepositoryHolder.connectionRepository ?: return

        val extras = sbn.notification.extras
        val title =
                extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body =
                extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                        ?: ""

        val tsSeconds = sbn.postTime / 1000L
        repo.sendNotification(
                NotificationMessage(
                        app = sbn.packageName,
                        title = title,
                        body = body,
                        timestamp = tsSeconds
                )
        )
    }
}
