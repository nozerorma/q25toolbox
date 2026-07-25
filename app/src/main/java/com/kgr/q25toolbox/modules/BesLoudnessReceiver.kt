package com.kgr.q25toolbox.modules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets besloudness_schedule.sh (a plain root shell script, which can't call Android
 * APIs directly) apply BesLoudness through the same in-process
 * AudioManager.setParameters() path the manual toggle uses - see
 * [BesLoudnessController] for why that's the only mechanism that actually works.
 * The daemon reaches this with an explicit `am broadcast -n <pkg>/.modules.
 * BesLoudnessReceiver`, which a root-owned shell can deliver to a non-exported
 * receiver even though third-party apps can't.
 */
class BesLoudnessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        when (intent.getIntExtra(EXTRA_STATE, -1)) {
            0 -> BesLoudnessController.applyLive(context, false)
            1 -> BesLoudnessController.applyLive(context, true)
        }
    }

    companion object {
        const val ACTION = "com.kgr.q25toolbox.SET_BESLOUDNESS"
        const val EXTRA_STATE = "state"
    }
}
