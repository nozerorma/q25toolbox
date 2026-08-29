package com.kgr.q25toolbox.modules

import android.content.Context
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.core.ShellResult

/**
 * Manages the global Firebase telemetry block.
 *
 * Neutralises Crashlytics, Analytics / Google Analytics, Performance Monitoring
 * and the Firebase master data-collection flag by forcing their persisted
 * "collection enabled" booleans to false (and injecting the flag where the SDK
 * expects the file but the app hasn't written one yet).
 *
 * Firebase SDKs re-derive these flags from the app manifest on every cold start,
 * so a one-shot pass gets undone. The installed script (block_telemetry.sh) is a
 * watchdog daemon: a burst of passes after boot, then a re-scan every
 * [INTERVAL_MIN] minutes, plus an extra burst whenever an app is installed or
 * removed.
 */
object TelemetryController {

    private const val SCRIPT_NAME = "block_telemetry.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "block_telemetry_template.sh"
    private const val LOCK = "/data/adb/.block_telemetry.lock"

    /** How often the watchdog re-scans and re-blocks. */
    private const val INTERVAL_MIN = 10

    /** Every persisted flag the block forces to false. */
    private val TELEMETRY_KEYS = listOf(
        "firebase_crashlytics_collection_enabled",
        "firebase_analytics_collection_enabled",
        "firebase_performance_collection_enabled",
        "firebase_data_collection_default_enabled",
        "measurement_enabled",
        "measurement_enabled_from_api",
    )

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

    /**
     * Runs one telemetry-disable pass live (same logic as the daemon's sweep):
     * flip every [TELEMETRY_KEYS] flag true -> false wherever it's persisted, and
     * inject the disable flag into the Crashlytics / measurement prefs files that
     * exist without one.
     */
    fun applyLive(): ShellResult {
        val re = TELEMETRY_KEYS.joinToString("|")
        val keys = TELEMETRY_KEYS.joinToString(" ")
        val d = "$" // keep $f / $k / $RE literal for the inner shell
        val cmd = """
            nsenter --mount=/proc/1/ns/mnt -- sh -c '
            KEYS="$keys"
            RE="$re"
            grep -rlE "\"(${d}RE)\" value=\"true\"" /data/data/*/shared_prefs 2>/dev/null | while read f; do
                [ -f "${d}f" ] || continue
                for k in ${d}KEYS; do
                    sed -i "s/\"${d}k\" value=\"true\"/\"${d}k\" value=\"false\"/g" "${d}f"
                done
            done
            find /data/data -name "com.google.firebase.crashlytics.xml" 2>/dev/null | while read f; do
                [ -f "${d}f" ] || continue
                grep -q "firebase_crashlytics_collection_enabled" "${d}f" || \
                    sed -i "s#</map>#    <boolean name=\"firebase_crashlytics_collection_enabled\" value=\"false\" />\n</map>#" "${d}f"
            done
            find /data/data -name "com.google.android.gms.measurement.prefs.xml" 2>/dev/null | while read f; do
                [ -f "${d}f" ] || continue
                grep -q "\"measurement_enabled\"" "${d}f" || \
                    sed -i "s#</map>#    <boolean name=\"measurement_enabled\" value=\"false\" />\n</map>#" "${d}f"
                grep -q "\"measurement_enabled_from_api\"" "${d}f" || \
                    sed -i "s#</map>#    <boolean name=\"measurement_enabled_from_api\" value=\"false\" />\n</map>#" "${d}f"
            done
            '
        """.trimIndent()
        return RootShell.run(cmd)
    }

    /** Apps carrying a Firebase telemetry prefs file (Crashlytics or Analytics/GA). */
    fun totalAffectedApps(): Int {
        val out = RootShell.run(
            "nsenter --mount=/proc/1/ns/mnt -- sh -c 'find /data/data " +
                "\\( -name com.google.firebase.crashlytics.xml -o -name com.google.android.gms.measurement.prefs.xml \\) " +
                "2>/dev/null | wc -l'"
        ).outString.trim()
        return out.toIntOrNull() ?: 0
    }

    /** Of [totalAffectedApps], how many have their collection flag forced to false. */
    fun totalBlockedApps(): Int {
        val out = RootShell.run(
            "nsenter --mount=/proc/1/ns/mnt -- sh -c '" +
                "c=0; " +
                "for f in \$(find /data/data \\( -name com.google.firebase.crashlytics.xml -o -name com.google.android.gms.measurement.prefs.xml \\) 2>/dev/null); do " +
                "grep -qE \"(firebase_crashlytics_collection_enabled|measurement_enabled|measurement_enabled_from_api)\\\" value=\\\"false\\\"\" \"\$f\" && c=\$((c+1)); " +
                "done; echo \$c'"
        ).outString.trim()
        return out.toIntOrNull() ?: 0
    }

    /** One Firebase surface an app persists a collection flag for, and whether we've forced it off. */
    data class Surface(val name: String, val blocked: Boolean)

    /** An app that carries at least one Firebase telemetry prefs file. */
    data class AppReport(val pkg: String, val surfaces: List<Surface>) {
        val fullyBlocked: Boolean get() = surfaces.all { it.blocked }
    }

    /**
     * Per-app telemetry status for the "what's blocked" viewer: every app with a
     * Crashlytics / GMS-measurement / Performance prefs file, and for each the
     * surfaces it exposes plus whether that surface is currently neutralised.
     * Sorted leaking-first, then by package.
     */
    fun blockReport(): List<AppReport> {
        val d = "$"
        val cmd = """
            nsenter --mount=/proc/1/ns/mnt -- sh -c '
            emit() {
                pat=${d}1; label=${d}2; shift 2
                for f in "${d}@"; do
                    [ -f "${d}f" ] || continue
                    p=${d}{f#/data/data/}; p=${d}{p%%/*}
                    if grep -qE "${d}pat\" value=\"false\"" "${d}f"; then
                        echo "${d}p|${d}label|1"
                    else
                        echo "${d}p|${d}label|0"
                    fi
                done
            }
            emit "firebase_crashlytics_collection_enabled" Crashlytics ${d}(find /data/data -name com.google.firebase.crashlytics.xml 2>/dev/null)
            emit "measurement_enabled(_from_api)?" Analytics ${d}(find /data/data -name com.google.android.gms.measurement.prefs.xml 2>/dev/null)
            emit "firebase_performance_collection_enabled" Performance ${d}(grep -rlE firebase_performance_collection_enabled /data/data/*/shared_prefs 2>/dev/null)
            '
        """.trimIndent()

        return RootShell.run(cmd).outString.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split("|")
                if (parts.size == 3 && parts[0].isNotEmpty()) {
                    Triple(parts[0], parts[1], parts[2] == "1")
                } else null
            }
            .groupBy({ it.first }, { Surface(it.second, it.third) })
            .map { (pkg, surfaces) ->
                AppReport(pkg, surfaces.distinctBy { it.name }.sortedBy { it.name })
            }
            .sortedWith(compareBy({ it.fullyBlocked }, { it.pkg }))
    }
}
