package com.kgr.q25toolbox.modules

import android.content.Context
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.core.ShellResult

/**
 * DT2W (Double Tap to Wake), implemented in software.
 *
 * The Q25 touch panel has no hardware/driver gesture-wake and the
 * double_tap_to_wake secure setting is not wired to anything on this ROM, so a
 * root watchdog daemon (/data/adb/service.d/dt2w.sh) watches the touchscreen
 * for a quick double-tap while the screen is off and injects KEYCODE_WAKEUP.
 *
 * Root-daemon based, so there's no live "sysfs write" - "enabled" means the
 * daemon is installed for next boot and launched now.
 */
object Dt2wController {

    private const val SCRIPT_NAME = "dt2w.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val LOCK = "/data/adb/.dt2w.lock"

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** Whether the DT2W watchdog daemon is currently running. */
    fun isRunning(): Boolean =
        RootShell.run("pgrep -f $SCRIPT_NAME >/dev/null 2>&1 && echo yes || echo no")
            .outString.trim() == "yes"

    /**
     * Enables (installs + launches) or disables (stops + removes) the daemon.
     * Any running instance is stopped first so a re-enable can't stack instances.
     */
    fun setEnabled(context: Context, enabled: Boolean): ShellResult {
        // "pkill -f" was found unreliable on this device's toybox build - it can report
        // success without actually killing the match. kill+pgrep does actually work.
        RootShell.run("kill \$(pgrep -f $SCRIPT_NAME) 2>/dev/null; rm -f $LOCK")

        return if (enabled) {
            val result = AssetInstaller.installFromAsset(context, SCRIPT_NAME, TARGET)
            // setsid detaches into its own session so it doesn't get dragged down when the
            // invoking root shell (a transient libsu session) is later recycled - see
            // ExtraDimController for the same fix and why it was needed.
            RootShell.run("nohup setsid sh $TARGET </dev/null >/dev/null 2>&1 &")
            result
        } else {
            AssetInstaller.removeFile(TARGET)
        }
    }
}
