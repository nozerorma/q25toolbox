package com.kgr.q25toolbox.modules

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.kgr.q25toolbox.core.RootShell

/**
 * Wires the Ticker Notifications master toggle to the system knobs it needs, granted/
 * reverted via root instead of sending the user through a manual settings screen
 * (matching KeyRemap/BesLoudness elsewhere in this app):
 *  - Notification listener access, via the `cmd notification` shell command (the
 *    same mechanism `adb shell cmd notification allow_listener` uses).
 *  - `heads_up_notifications_enabled`, the AOSP SystemUI global setting that gates
 *    heads-up popups - this is the actual mechanism the user asked to have disabled
 *    while ticker mode is on. NOTE: this ROM is BenOS/MTK, not stock AOSP/LineageOS
 *    (see the q25-device-facts project memory), and this app has repeatedly found
 *    AOSP-looking knobs to be no-ops here (e.g. `am compat DOWNSCALE_*`) - verify
 *    live on-device that heads-up actually stops before trusting this.
 *
 * Heads-up suppression is a **latched** state: it goes off when the module is enabled
 * and back on when it's disabled, and is re-asserted whenever the listener (re)connects.
 * It used to be flipped per notification instead - off in [TickerOverlayController.show],
 * back on when the ticker faded out or when a notification was filtered out - which was
 * an unwinnable race: SystemUI decides whether a notification becomes a heads-up at
 * post time, in parallel with our listener callback, so whether the popup appeared came
 * down to which side won. Since the flag spent most of its life back at 1, apps whose
 * notification arrived while no ticker was on screen (Substack, among others) popped up
 * a heads-up anyway. Nothing can restore heads-up "just for this notification" after the
 * fact either, so the filtered-notification path can't work that way at all.
 *
 * Consequence of latching: while the module is on, blocked apps/categories and
 * below-min-importance notifications get no heads-up either - they post silently to the
 * shade instead. Incoming calls and alarms are unaffected because those use full-screen
 * intents, which this setting doesn't gate.
 *
 * The ticker window itself no longer needs the SYSTEM_ALERT_WINDOW/"draw over other
 * apps" permission at all - it's rendered as a TYPE_ACCESSIBILITY_OVERLAY window (see
 * [com.kgr.q25toolbox.service.TickerOverlayController]), which only requires the app's
 * own accessibility service ([com.kgr.q25toolbox.service.Q25AccessibilityService]) to
 * be enabled.
 */
object TickerController {

    private const val SERVICE_COMPONENT =
        "com.kgr.q25toolbox/com.kgr.q25toolbox.service.TickerNotificationListenerService"

    fun isNotificationAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            RootShell.run(
                "cmd notification allow_listener $SERVICE_COMPONENT; " +
                    "settings put global heads_up_notifications_enabled 0"
            )
        } else {
            RootShell.run(
                "cmd notification disallow_listener $SERVICE_COMPONENT; " +
                    "settings put global heads_up_notifications_enabled 1"
            )
        }
        TickerSettings.setEnabledFlag(context, enabled)
    }

    /**
     * Re-assert the heads-up flag to match the module's enabled state. Called when the
     * notification listener connects (app start, reboot, listener rebind) so a reboot or
     * anything else that reset the global setting doesn't silently bring heads-up popups
     * back while the ticker is still on.
     *
     * Blocking root call - never call this from the main thread. It shares a process (and
     * therefore a main looper) with the accessibility service, whose key-event filter has
     * a 500ms budget before the system gives up and delivers the key to the app anyway.
     */
    fun syncHeadsUpSuppression(context: Context) {
        val value = if (TickerSettings.isEnabled(context)) 0 else 1
        RootShell.run("settings put global heads_up_notifications_enabled $value")
    }
}
