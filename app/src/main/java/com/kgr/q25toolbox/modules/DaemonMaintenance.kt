package com.kgr.q25toolbox.modules

import android.content.Context
import com.kgr.q25toolbox.core.RootShell

/**
 * Repairs every installed watchdog daemon once per app launch, and removes
 * daemons left behind by features that no longer exist or are disabled.
 *
 * Each module's own screen already self-heals when opened (see
 * ExtraDimScreen and friends): it compares the installed script's content
 * against what's current, not just whether the process is alive, since a
 * stale script loops forever and holds its lock exactly like a healthy one
 * (see [AssetInstaller.matchesAsset][com.kgr.q25toolbox.core.AssetInstaller.matchesAsset]).
 * But that only runs for screens someone actually opens - a module nobody's
 * visited since the app updated keeps running whatever was installed however
 * long ago, indefinitely. This runs the same check for every module
 * unconditionally from [HomeScreen], so an app update fixes everything on
 * next launch, not just whatever the user happens to click into. This also
 * means it's the same code path that repairs other people's installs, not
 * just this device - it runs from any device on any app update, regardless
 * of how the stale script originally got there (old build, interrupted
 * update, etc.), since it only looks at what's on disk now.
 */
object DaemonMaintenance {

    /**
     * Script name -> lock file for daemons this app has installed in a past
     * version but no longer ships a live module for. Removed unconditionally
     * on sweep - there's no feature left to heal them into. Add an entry here
     * whenever a daemon-based module is removed or hidden (see Screen.kt's
     * systemScreens comment for why DT2W is here: repeated kernel crashes).
     */
    private val DEPRECATED_SCRIPTS = listOf(
        "dt2w.sh" to ".dt2w.lock",
    )

    /** Repairs every currently-enabled module's daemon and removes deprecated ones. */
    fun sweep(context: Context) {
        if (ExtraDimController.isScheduleEnabled()) {
            val start = ExtraDimController.persistedStartMinutes()
            val end = ExtraDimController.persistedEndMinutes()
            if (!ExtraDimController.isScheduleHealthy(context, start, end)) {
                ExtraDimController.setScheduleEnabled(context, true, start, end)
            }
        }
        if (BesLoudnessController.isScheduleEnabled()) {
            val start = BesLoudnessController.persistedStartMinutes()
            val end = BesLoudnessController.persistedEndMinutes()
            if (!BesLoudnessController.isScheduleHealthy(context, start, end)) {
                BesLoudnessController.setScheduleEnabled(context, true, start, end)
            }
        }
        if (TelemetryController.isPersisted() && !TelemetryController.isHealthy(context)) {
            TelemetryController.setEnabled(context, true)
        }
        if (BtIdleController.isPersisted()) {
            val minutes = BtIdleController.persistedTimeout() ?: BtIdleController.DEFAULT_TIMEOUT
            if (!BtIdleController.isHealthy(context, minutes)) {
                BtIdleController.setEnabled(context, true, minutes)
            }
        }
        if (LocationIdleController.isPersisted()) {
            val minutes = LocationIdleController.persistedTimeout() ?: LocationIdleController.DEFAULT_TIMEOUT
            if (!LocationIdleController.isHealthy(context, minutes)) {
                LocationIdleController.setEnabled(context, true, minutes)
            }
        }
        if (RecentsTweaksController.isPersisted() && !RecentsTweaksController.isHealthy(context)) {
            RecentsTweaksController.setNativeGridPatch(context, true)
        }

        for ((script, lock) in DEPRECATED_SCRIPTS) {
            RootShell.run("kill \$(pgrep -f $script) 2>/dev/null; rm -f /data/adb/service.d/$script /data/adb/$lock")
        }
    }
}
