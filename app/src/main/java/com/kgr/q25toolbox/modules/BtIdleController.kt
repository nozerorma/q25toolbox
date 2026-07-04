package com.kgr.q25toolbox.modules

import android.content.Context
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.core.ShellResult

/**
 * Auto-disable Bluetooth when idle.
 *
 * A leftover-on / always-bonded Bluetooth radio holds hal_bluetooth_lock and
 * prevents deep sleep (a major overnight drain), even with nothing actively
 * connected. This installs a small root watchdog daemon
 * (/data/adb/service.d/bt_idle.sh from bt_idle_template.sh) that turns Bluetooth
 * off after [DEFAULT_TIMEOUT]/chosen minutes with no device connected.
 *
 * Root-script based, so the app needs no Bluetooth / foreground-service /
 * boot-receiver permissions. The script persists across reboots via service.d
 * and is also launched live here on enable.
 */
object BtIdleController {

    private const val SCRIPT_NAME = "bt_idle.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "bt_idle_template.sh"
    private const val LOCK = "/data/adb/.bt_idle.lock"

    val TIMEOUT_OPTIONS = listOf(5, 10, 15, 30, 60)
    const val DEFAULT_TIMEOUT = 15

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** Whether the watchdog daemon is currently running. */
    fun isRunning(): Boolean =
        RootShell.run("pgrep -f $SCRIPT_NAME >/dev/null 2>&1 && echo yes || echo no")
            .outString.trim() == "yes"

    /** The timeout the persisted script targets, or null if not installed. */
    fun persistedTimeout(): Int? {
        val content = AssetInstaller.readFile(TARGET)
        return Regex("""TIMEOUT=(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Enables (installs + launches) or disables (stops + removes) the watchdog.
     * Any running instance is stopped first so a changed [minutes] takes effect
     * immediately.
     */
    fun setEnabled(context: Context, enabled: Boolean, minutes: Int): ShellResult {
        // Stop any running daemon and clear its lock so we don't stack instances.
        RootShell.run("pkill -f $SCRIPT_NAME 2>/dev/null; rm -f $LOCK")

        return if (enabled) {
            val result = AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
                raw.replace("__TIMEOUT_MIN__", minutes.toString())
            }
            // Launch live, detached from the app's shell so it survives.
            RootShell.run("nohup sh $TARGET >/dev/null 2>&1 &")
            result
        } else {
            AssetInstaller.removeFile(TARGET)
        }
    }
}
