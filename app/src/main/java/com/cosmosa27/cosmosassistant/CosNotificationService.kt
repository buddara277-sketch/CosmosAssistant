package com.cosmosa27.cosmosassistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class CosNotificationService : NotificationListenerService() {

    companion object {
        @Volatile
        var latestNotifications: List<String> = emptyList()
            private set
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val message = extras.getCharSequence("android.text")?.toString().orEmpty()

        if (title.isBlank() && message.isBlank()) return

        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        val line = listOf(appName, title, message)
            .filter { it.isNotBlank() }
            .joinToString(": ")

        synchronized(this) {
            latestNotifications = (listOf(line) + latestNotifications)
                .distinct()
                .take(20)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // История уведомлений сохраняется до перезапуска приложения.
    }
}
