package com.kgr.q25toolbox.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kgr.q25toolbox.modules.TickerColorResolver
import com.kgr.q25toolbox.modules.TickerController
import com.kgr.q25toolbox.modules.TickerSettings
import java.util.concurrent.Executors

/**
 * Watches posted notifications and hands qualifying ones to [TickerOverlayController]
 * instead of letting them show as heads-up (heads-up itself is killed system-wide by
 * [TickerController] for as long as this module is enabled - see its doc for why that
 * is latched rather than toggled per notification).
 *
 * Granted via root (`cmd notification allow_listener`) rather than the manual
 * "Notification access" settings screen - see [com.kgr.q25toolbox.modules.TickerController].
 */
class TickerNotificationListenerService : NotificationListenerService() {

    private var lastTickerPackage: String? = null
    private var lastTickerText: String? = null
    private var lastTickerTime: Long = 0L

    // Root calls must never run on this service's main thread: it is the same main looper
    // the accessibility service's onKeyEvent is delivered on, and a blocked looper there
    // means keystrokes time out of the accessibility key filter and reach the app raw.
    private val worker = Executors.newSingleThreadExecutor()

    override fun onListenerConnected() {
        super.onListenerConnected()
        val context = applicationContext
        worker.execute { TickerController.syncHeadsUpSuppression(context) }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        val context = applicationContext
        if (!TickerSettings.isEnabled(context)) return
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return

        // 1. App filter
        if (sbn.packageName in TickerSettings.blockedApps(context)) return

        // 2. Group summary filter
        val isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (isGroupSummary) return

        // 3. Ongoing filter
        val isOngoing = sbn.isOngoing ||
            (notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
        val includeOngoing = TickerSettings.includeOngoing(context)
        if (isOngoing && !includeOngoing) return

        // 4. Category filter (with intelligent fallback for apps using MessagingStyle/CallStyle without setting category)
        val category = resolveCategory(notification, sbn.packageName)
        if (category != null && category in TickerSettings.blockedCategories(context)) return

        // 5. Importance filter (ongoing notifications explicitly allowed by user bypass minImportance floor)
        val ranking = Ranking()
        if (rankingMap != null && rankingMap.getRanking(sbn.key, ranking)) {
            if (!isOngoing && ranking.importance < TickerSettings.minImportance(context)) return
        }

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val maxLines = TickerSettings.maxBodyLines(context)
        val bodyExcerpt = body.lineSequence().filter { it.isNotBlank() }.take(maxLines).joinToString("  ")
        val tickerText = listOf(title, bodyExcerpt).filter { it.isNotBlank() }.joinToString(": ")
        if (tickerText.isBlank()) return

        // Avoid re-triggering rapid duplicate animations for ongoing progress updates
        val now = System.currentTimeMillis()
        if (isOngoing && sbn.packageName == lastTickerPackage && tickerText == lastTickerText && (now - lastTickerTime) < 3000L) {
            return
        }
        lastTickerPackage = sbn.packageName
        lastTickerText = tickerText
        lastTickerTime = now

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
            backgroundColor = TickerColorResolver.resolveBackgroundColor(context, sbn.packageName, notification),
        )
    }

    private fun resolveCategory(notification: Notification, packageName: String): String? {
        val cat = notification.category
        if (!cat.isNullOrBlank()) return cat

        val extras = notification.extras ?: return inferFromPackage(packageName)

        if (extras.containsKey(Notification.EXTRA_MESSAGES) ||
            extras.containsKey(Notification.EXTRA_MESSAGING_PERSON) ||
            extras.containsKey("android.messagingStyleUser") ||
            extras.containsKey(Notification.EXTRA_CONVERSATION_TITLE)
        ) {
            return Notification.CATEGORY_MESSAGE
        }

        if (extras.containsKey(Notification.EXTRA_CALL_TYPE) ||
            extras.containsKey(Notification.EXTRA_CALL_PERSON) ||
            extras.containsKey("android.callType")
        ) {
            return Notification.CATEGORY_CALL
        }

        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            extras.containsKey(Notification.EXTRA_COMPACT_ACTIONS)
        ) {
            return Notification.CATEGORY_TRANSPORT
        }

        if (extras.containsKey(Notification.EXTRA_PROGRESS) ||
            extras.containsKey(Notification.EXTRA_PROGRESS_MAX)
        ) {
            return Notification.CATEGORY_PROGRESS
        }

        return inferFromPackage(packageName)
    }

    private fun inferFromPackage(packageName: String): String? {
        val pkg = packageName.lowercase()
        return when {
            pkg.contains("messaging") || pkg.contains("whatsapp") || pkg.contains("telegram") ||
                pkg.contains("signal") || pkg.contains("sms") || pkg.contains("mms") -> Notification.CATEGORY_MESSAGE
            pkg.contains("dialer") || pkg.contains("telecom") || pkg.contains("phone") -> Notification.CATEGORY_CALL
            pkg.contains("gmail") || pkg.contains("mail") || pkg.contains("email") || pkg.contains("outlook") -> Notification.CATEGORY_EMAIL
            pkg.contains("clock") || pkg.contains("alarm") || pkg.contains("timer") -> Notification.CATEGORY_ALARM
            pkg.contains("spotify") || pkg.contains("music") || pkg.contains("youtube") || pkg.contains("audio") -> Notification.CATEGORY_TRANSPORT
            else -> null
        }
    }
}
