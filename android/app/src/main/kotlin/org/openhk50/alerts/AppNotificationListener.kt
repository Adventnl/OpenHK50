package org.openhk50.alerts

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.openhk50.OpenHkApp

internal class AppNotificationListener : NotificationListenerService() {
    private val activeKeys = mutableSetOf<String>()

    override fun onListenerConnected() {
        activeKeys.clear()
        activeNotifications.orEmpty().mapTo(activeKeys) { it.key }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val firstPost = activeKeys.add(sbn.key)
        if (sbn.packageName == packageName || sbn.notification.flags and IGNORED_FLAGS != 0) return
        if (!firstPost && sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) return
        val ranking = Ranking()
        if (rankingMap.getRanking(sbn.key, ranking) && !ranking.matchesInterruptionFilter()) return
        (application as OpenHkApp).phoneVibration.notification(sbn.packageName)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeKeys.remove(sbn.key)
    }

    companion object {
        private const val IGNORED_FLAGS = Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT
    }
}
