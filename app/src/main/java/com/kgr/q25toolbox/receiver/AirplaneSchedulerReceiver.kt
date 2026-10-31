package com.kgr.q25toolbox.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kgr.q25toolbox.modules.AirplaneScheduler

/**
 * Entry point for everything that can move a schedule boundary: the alarm itself, a reboot, an app
 * update (alarms are dropped on both) and clock / timezone changes. All of them just re-run
 * [AirplaneScheduler.refresh], which is state-based, so none needs its own handling.
 */
class AirplaneSchedulerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (AirplaneScheduler.load(context).none { it.enabled }) return
        // Root calls block: leave the main thread, and keep the receiver alive until they finish.
        val result = goAsync()
        Thread {
            try {
                AirplaneScheduler.refresh(context.applicationContext)
            } finally {
                result.finish()
            }
        }.start()
    }
}
