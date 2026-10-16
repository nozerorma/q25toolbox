package com.kgr.q25toolbox.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kgr.q25toolbox.modules.GestureSettings

/**
 * Lets another app (Key Mapper: Send intent, Broadcast) run one of this app's actions, so the Recents key can be
 * handled there and still open our overlay, go to the previous app, and so on.
 *
 * Intent: explicit component `com.kgr.q25toolbox/.service.RunActionReceiver` (or the action [ACTION_RUN]) with the
 * string extra [EXTRA_ACTION] = a [GestureSettings.Action] name (default RECENTS). It needs the accessibility service
 * running, and it is ignored on the keyguard. Exported: any app can send it, which is harmless for these actions but
 * means any app can, say, open the Recents overlay.
 */
class RunActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val named = intent.getStringExtra(EXTRA_ACTION)
        val action = if (named == null) GestureSettings.Action.RECENTS else GestureSettings.parseAction(named)
        if (action == null) { Log.w("Q25Toolbox", "RunActionReceiver: unknown action '$named'"); return }
        // The receiver is registered twice (manifest + dynamically in the accessibility service) and an
        // explicit broadcast can match both; the enlarger's trigger() toggles, so a double delivery would
        // cancel itself out. Both receivers run on this app's main thread, so no synchronization is needed.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAcceptedAt < DELIVER_DEBOUNCE_MS) {
            Log.d("Q25Toolbox", "RunActionReceiver: duplicate delivery of '$named' ignored")
            return
        }
        lastAcceptedAt = now
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) return
        val svc = Q25AccessibilityService.instance
        if (svc == null) { Log.w("Q25Toolbox", "RunActionReceiver: accessibility service is not running"); return }
        svc.performEdgeAction(action)
    }

    companion object {
        const val ACTION_RUN = "com.kgr.q25toolbox.action.RUN"
        const val EXTRA_ACTION = "action"
        @Volatile private var lastAcceptedAt = 0L
        private const val DELIVER_DEBOUNCE_MS = 500L
    }
}
