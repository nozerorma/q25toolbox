package com.kgr.q25toolbox.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kgr.q25toolbox.modules.TickerColorResolver
import com.kgr.q25toolbox.modules.TickerController
import com.kgr.q25toolbox.modules.TickerFilter
import com.kgr.q25toolbox.modules.TickerSettings
import java.util.concurrent.Executors

/**
 * Watches posted notifications and hands qualifying ones to [TickerOverlayController],
 * asking [TickerController] to suppress the heads-up popup for those same notifications -
 * and only those, so a blocklisted app keeps popping up as normal. See TickerController for
 * why that suppression is a race this can only narrow, not win outright.
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
        worker.execute { TickerController.syncSystemState(context) }
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

        // App / group-summary / ongoing / category filters, shared with the assistant service.
        if (!TickerFilter.claims(context, sbn, packageName)) return

        // Minimum-importance floor. Lives here rather than in TickerFilter because it needs
        // the RankingMap, which doesn't exist yet at the assistant's enqueue-time hook - see
        // TickerFilter's doc for why that asymmetry is safe. (Ongoing notifications the user
        // explicitly opted into bypass the floor.)
        val ranking = Ranking()
        if (rankingMap != null && rankingMap.getRanking(sbn.key, ranking)) {
            if (!TickerFilter.isOngoing(sbn) && ranking.importance < TickerSettings.minImportance(context)) return
        }

        // Nothing can render a ticker without the accessibility service (the overlay is a
        // TYPE_ACCESSIBILITY_OVERLAY window), and heads-up popups don't happen on a dark
        // screen anyway - in both cases leave the notification entirely alone rather than
        // suppress a popup and put nothing in its place.
        if (!TickerOverlayController.canShow(context)) return

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
        if (TickerFilter.isOngoing(sbn) && sbn.packageName == lastTickerPackage && tickerText == lastTickerText && (now - lastTickerTime) < 3000L) {
            return
        }
        lastTickerPackage = sbn.packageName
        lastTickerText = tickerText
        lastTickerTime = now

        // Ask for suppression here, the first moment we're committed to showing a ticker -
        // ahead of the icon load and palette colour extraction below, which are the slow
        // part of this callback. Every millisecond earlier is a millisecond less of the
        // window in which SystemUI can decide to pop this notification up as well.
        TickerController.suppressHeadsUpForTicker()

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
}
