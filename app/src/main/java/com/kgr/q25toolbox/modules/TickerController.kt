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
                    "settings put global heads_up_notifications_enabled 1"
            )
        } else {
            RootShell.run(
                "cmd notification disallow_listener $SERVICE_COMPONENT; " +
                    "settings put global heads_up_notifications_enabled 1"
            )
        }
        TickerSettings.setEnabledFlag(context, enabled)
    }
}
