package com.kgr.q25toolbox.modules

import android.content.Context
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.core.ShellResult

/**
 * Manages global Firebase Crashlytics telemetry block.
 *
 * Apps rewrite com.google.firebase.crashlytics.xml at runtime, so a one-shot
 * boot pass gets undone. The installed script (block_telemetry.sh) is a
 * watchdog daemon that re-applies the block every [INTERVAL_MIN] minutes.
 */
object TelemetryController {

    private const val SCRIPT_NAME = "block_telemetry.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "block_telemetry_template.sh"
    private const val LOCK = "/data/adb/.block_telemetry.lock"

    /** How often the watchdog re-scans and re-blocks. */
    private const val INTERVAL_MIN = 30

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** Whether the watchdog daemon is currently running. */
    fun isRunning(): Boolean =
        RootShell.run("pgrep -f $SCRIPT_NAME >/dev/null 2>&1 && echo yes || echo no")
            .outString.trim() == "yes"

    /**
     * Whether the installed daemon is both alive AND running the script we'd install
     * today - see [AssetInstaller.matchesAsset] for why a bare "is it running" check
     * isn't enough. Confirmed in practice: a device's block_telemetry.sh was still
     * running the pre-hardening PID-lock check from an older build, invisibly, since
     * the process itself never dies either way.
     */
    fun isHealthy(context: Context): Boolean =
        isRunning() && AssetInstaller.matchesAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
            raw.replace("__INTERVAL_MIN__", INTERVAL_MIN.toString())
        }

    fun setEnabled(context: Context, enabled: Boolean): ShellResult {
        // Stop any running daemon and clear its lock so we don't stack instances.
        // "pkill -f" was found unreliable on this device's toybox build - it can report
        // success without actually killing the match. kill+pgrep does actually work.
        RootShell.run("kill \$(pgrep -f $SCRIPT_NAME) 2>/dev/null; rm -f $LOCK")

        return if (enabled) {
            val result = AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
                raw.replace("__INTERVAL_MIN__", INTERVAL_MIN.toString())
            }
            // Launch live, detached, so blocking starts now without a reboot. setsid
            // detaches into its own session so it doesn't get dragged down when the
            // invoking root shell (a transient libsu session) is later recycled -
            // see ExtraDimController for the same fix and why it was needed.
            RootShell.run("nohup setsid sh $TARGET </dev/null >/dev/null 2>&1 &")
            result
        } else {
            AssetInstaller.removeFile(TARGET)
        }
    }

    /** Runs the telemetry disable loop live. */
    fun applyLive(): ShellResult {
        val cmd = """
            nsenter --mount=/proc/1/ns/mnt -- sh -c '
            find /data/data/ -name "com.google.firebase.crashlytics.xml" 2>/dev/null | while read f; do
                if [ ! -f "${'$'}f" ]; then continue; fi
                if grep -q "firebase_crashlytics_collection_enabled" "${'$'}f"; then
                    sed -i "s/firebase_crashlytics_collection_enabled\" value=\"true\"/firebase_crashlytics_collection_enabled\" value=\"false\"/g" "${'$'}f"
                else
                    sed -i "s#</map>#    <boolean name=\"firebase_crashlytics_collection_enabled\" value=\"false\" />\n</map>#g" "${'$'}f"
                fi
            done
            '
        """.trimIndent()
        return RootShell.run(cmd)
    }

    /** Returns count of apps with Crashlytics XML files. */
    fun totalAffectedApps(): Int {
        val out = RootShell.run("nsenter --mount=/proc/1/ns/mnt -- find /data/data/ -name \"com.google.firebase.crashlytics.xml\" 2>/dev/null | wc -l").outString.trim()
        return out.toIntOrNull() ?: 0
    }

    /** Returns count of apps with Crashlytics set to false. */
    fun totalBlockedApps(): Int {
        val out = RootShell.run("nsenter --mount=/proc/1/ns/mnt -- sh -c 'find /data/data/ -name \"com.google.firebase.crashlytics.xml\" 2>/dev/null | xargs grep -l \"firebase_crashlytics_collection_enabled\\\" value=\\\"false\\\"\" 2>/dev/null | wc -l'").outString.trim()
        return out.toIntOrNull() ?: 0
    }
}
