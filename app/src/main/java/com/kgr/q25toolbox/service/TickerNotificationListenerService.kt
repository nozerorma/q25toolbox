package com.kgr.q25toolbox.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kgr.q25toolbox.modules.TickerColorResolver
import com.kgr.q25toolbox.modules.TickerSettings

/**
 * Watches posted notifications and hands qualifying ones to [TickerOverlayController]
 * instead of letting them show as heads-up (heads-up itself is killed system-wide by
 * [com.kgr.q25toolbox.modules.TickerController] while this module is enabled).
 *
 * Granted via root (`cmd notification allow_listener`) rather than the manual
 * "Notification access" settings screen - see [com.kgr.q25toolbox.modules.TickerController].
 */
class TickerNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        val context = applicationContext
        if (!TickerSettings.isEnabled(context)) return
        if (sbn.packageName == packageName) return
        if (sbn.packageName in TickerSettings.blockedApps(context)) return

        val notification = sbn.notification ?: return
        val isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (isGroupSummary) return

        val isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (isOngoing && !TickerSettings.includeOngoing(context)) return

        val category = notification.category
        if (category != null && category in TickerSettings.blockedCategories(context)) return

        val ranking = Ranking()
        if (rankingMap != null && rankingMap.getRanking(sbn.key, ranking)) {
            if (ranking.importance < TickerSettings.minImportance(context)) return
        }

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val maxLines = TickerSettings.maxBodyLines(context)
        val bodyExcerpt = body.lineSequence().filter { it.isNotBlank() }.take(maxLines).joinToString("  ")
        val tickerText = listOf(title, bodyExcerpt).filter { it.isNotBlank() }.joinToString(": ")
        if (tickerText.isBlank()) return

        val icon = try {
            notification.smallIcon?.loadDrawable(context)
        } catch (_: Exception) {
            null
        }

        TickerOverlayController.show(
            context = context,
            icon = icon,
            text = tickerText,
            contentIntent = if (TickerSettings.isTapToOpen(context)) notification.contentIntent else null,
            backgroundColor = TickerColorResolver.resolveBackgroundColor(context, sbn.packageName),
        )
    }
}
