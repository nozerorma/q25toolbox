package com.kgr.q25toolbox.modules

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.kgr.q25toolbox.core.RootShell
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Wires the Ticker Notifications master toggle to the system knobs it needs, granted/
 * reverted via root instead of sending the user through a manual settings screen
 * (matching KeyRemap/BesLoudness elsewhere in this app):
 *  - Notification listener access, via the `cmd notification` shell command (the
 *    same mechanism `adb shell cmd notification allow_listener` uses).
 *  - `heads_up_notifications_enabled`, the AOSP SystemUI global setting gating heads-up
 *    popups - see below, it's the only lever available here and it is a blunt one.
 *
 * The ticker window itself needs no SYSTEM_ALERT_WINDOW/"draw over other apps"
 * permission at all - it's rendered as a TYPE_ACCESSIBILITY_OVERLAY window (see
 * [com.kgr.q25toolbox.service.TickerOverlayController]), which only requires the app's
 * own accessibility service ([com.kgr.q25toolbox.service.Q25AccessibilityService]) to
 * be enabled.
 *
 * ## Heads-up suppression, and why it is the way it is
 *
 * Wanted: the notifications the ticker shows don't also pop up; the ones it doesn't (a
 * blocklisted app or category) pop up exactly as they would with this module off.
 *
 * `heads_up_notifications_enabled` cannot express that - it's one global switch - so this
 * keeps it **enabled as the steady state** and turns it off only around a ticker. That
 * gets the blocklist right by construction (nothing is touched for a notification we
 * decline) at the cost of a race for the ones we do show: SystemUI decides heads-up-or-not
 * when a notification is posted, at roughly the same moment our listener hears about it, so
 * occasionally one slips through and shows both. That is the known, accepted defect.
 *
 * Two other designs were tried and are worse:
 *  - **Latching it off** for as long as the module is enabled (v2.1) removes the race, but
 *    blocklisted apps then get no popup either - which is the opposite of what
 *    blocklisting an app is for. Reverted in v2.1.1.
 *  - **A NotificationAssistantService**, whose `onNotificationEnqueued` hook runs *before*
 *    SystemUI sees the notification and could demote just that one, is the mechanism this
 *    actually wants. It is not available: the class is @SystemApi, and more to the point
 *    `cmd notification allow_assistant` silently refuses a non-privileged component -
 *    verified on-device, including with the assistant slot emptied first, where granting
 *    ours left it `null`. It would need this app to be a privileged/system app.
 *
 * What *is* mitigated: the suppression write is issued as early as possible (before the
 * icon and palette-color work the ticker needs, which is the slow part), never on the main
 * thread, and it lingers briefly after the ticker ends ([HEADS_UP_GRACE_MS]) so that a
 * burst of notifications races only once instead of once per notification.
 */
object TickerController {

    private const val SERVICE_COMPONENT =
        "com.kgr.q25toolbox/com.kgr.q25toolbox.service.TickerNotificationListenerService"

    /**
     * How long heads-up stays suppressed after a ticker finishes. Notifications tend to
     * arrive in bursts (a batch of messages syncing at once), and restoring the setting the
     * instant the banner faded meant every notification in the burst got its own
     * independent chance to slip a popup through. Kept short so a blocklisted notification
     * arriving right behind a tickered one is unlikely to be caught by it.
     */
    private const val HEADS_UP_GRACE_MS = 2000L

    // Every root call here runs on this one thread. It must never be the main looper: that
    // is the same looper the accessibility service's onKeyEvent is delivered on, and
    // blocking it past the key filter's ~500ms budget makes the framework hand the keypress
    // to the app as well - which is how a single keystroke came out doubled.
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var pendingRestore: ScheduledFuture<*>? = null

    fun isNotificationAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun setEnabled(context: Context, enabled: Boolean) {
        val listenerCmd = if (enabled) "allow_listener" else "disallow_listener"
        RootShell.run(
            "cmd notification $listenerCmd $SERVICE_COMPONENT; " +
                "settings put global heads_up_notifications_enabled 1"
        )
        TickerSettings.setEnabledFlag(context, enabled)
    }

    /**
     * Put `heads_up_notifications_enabled` back to its steady state of 1 on listener connect
     * (app start, reboot, listener rebind). Matters on upgrade in particular: v2.1 latched it
     * to 0 and nothing else would ever put it back, so a device coming from that build would
     * otherwise keep swallowing every popup.
     *
     * Blocking root call - not for the main thread.
     */
    fun syncSystemState(context: Context) {
        RootShell.run("settings put global heads_up_notifications_enabled 1")
    }

    /**
     * Suppress heads-up because a ticker is about to be shown for this notification. Returns
     * immediately; the write happens on [exec]. Safe to call from the main thread.
     */
    fun suppressHeadsUpForTicker() {
        synchronized(this) {
            pendingRestore?.cancel(false)
            pendingRestore = null
        }
        exec.execute { RootShell.run("settings put global heads_up_notifications_enabled 0") }
    }

    /**
     * The ticker is done - restore heads-up after [HEADS_UP_GRACE_MS]. Returns immediately.
     * A ticker starting in the meantime cancels the restore rather than letting it land
     * mid-banner. Safe to call from the main thread.
     */
    fun releaseHeadsUpAfterTicker() {
        synchronized(this) {
            pendingRestore?.cancel(false)
            pendingRestore = exec.schedule(
                { RootShell.run("settings put global heads_up_notifications_enabled 1") },
                HEADS_UP_GRACE_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }
}
