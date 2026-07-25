package com.kgr.q25toolbox.modules

import android.content.Context
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.core.ShellResult

/**
 * Auto-disable Location when idle, mirroring [BtIdleController].
 *
 * Installs a small root watchdog daemon (/data/adb/service.d/location_idle.sh
 * from location_idle_template.sh) that turns Location off after [DEFAULT_TIMEOUT]/
 * chosen minutes with the GPS provider continuously idle (see the template for
 * why that - not GMS's near-always-registered background listeners - is the
 * right "actively in use" signal).
 *
 * Root-script based, so the app needs no location / foreground-service /
 * boot-receiver permissions. The script persists across reboots via service.d
 * and is also launched live here on enable.
 */
object LocationIdleController {

    private const val SCRIPT_NAME = "location_idle.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "location_idle_template.sh"
    private const val LOCK = "/data/adb/.location_idle.lock"

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
     * Whether the installed daemon is both alive AND running the script we'd install
     * today for [minutes] - see [AssetInstaller.matchesAsset] for why a bare "is it
     * running" check isn't enough (a stale script from an older build, or one still
     * targeting a since-changed timeout, loops forever too, silently enforcing the
     * wrong thing).
     */
    fun isHealthy(context: Context, minutes: Int): Boolean =
        isRunning() && AssetInstaller.matchesAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
            raw.replace("__TIMEOUT_MIN__", minutes.toString())
        }

    /**
     * Enables (installs + launches) or disables (stops + removes) the watchdog.
     * Any running instance is stopped first so a changed [minutes] takes effect
     * immediately.
     */
    fun setEnabled(context: Context, enabled: Boolean, minutes: Int): ShellResult {
        // Stop any running daemon and clear its lock so we don't stack instances.
        // "pkill -f" was found unreliable on this device's toybox build - it can report
        // success without actually killing the match. kill+pgrep does actually work.
        RootShell.run("kill \$(pgrep -f $SCRIPT_NAME) 2>/dev/null; rm -f $LOCK")

        return if (enabled) {
            val result = AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
                raw.replace("__TIMEOUT_MIN__", minutes.toString())
            }
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
