package com.kgr.q25toolbox.modules

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification

/**
 * The single answer to "is this notification one the ticker is going to show?".
 *
 * Extracted from [com.kgr.q25toolbox.service.TickerNotificationListenerService] so the
 * decision has one home: it drives both whether a ticker appears and whether heads-up is
 * suppressed for it ([TickerController.suppressHeadsUpForTicker]), and those two must never
 * disagree - suppressing something no ticker then shows swallows the notification silently.
 *
 * The minimum-importance floor deliberately lives in the listener rather than here, because
 * it needs the RankingMap.
 */
object TickerFilter {

    /**
     * Whether the ticker claims [sbn] - i.e. it isn't from us, isn't a group summary, and
     * isn't excluded by the app or category blocklist.
     *
     * False means **hands off**: no ticker, and heads-up left alone, so the notification
     * behaves exactly as it would with this module turned off. That is the point of
     * blocklisting an app - "leave this one alone", not "silence this one".
     */
    fun claims(context: Context, sbn: StatusBarNotification, selfPackage: String): Boolean {
        if (sbn.packageName == selfPackage) return false
        val notification = sbn.notification ?: return false

        if (sbn.packageName in TickerSettings.blockedApps(context)) return false

        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false

        val isOngoing = isOngoing(sbn)
        if (isOngoing && !TickerSettings.includeOngoing(context)) return false

        val category = resolveCategory(notification, sbn.packageName)
        if (category != null && category in TickerSettings.blockedCategories(context)) return false

        return true
    }

    fun isOngoing(sbn: StatusBarNotification): Boolean {
        val flags = sbn.notification?.flags ?: 0
        return sbn.isOngoing ||
            (flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
    }

    /** Category, with a fallback for apps using MessagingStyle/CallStyle without setting one. */
    fun resolveCategory(notification: Notification, packageName: String): String? {
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
