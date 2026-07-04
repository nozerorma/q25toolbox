package com.kgr.q25toolbox.modules

import android.content.Context
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.core.ShellResult

/**
 * Persistent wireless ADB on a user-chosen static port.
 *
 * Persistence writes /data/adb/service.d/adb_wireless.sh from
 * adb_wireless_template.sh with __PORT__ substituted. The script both
 * enables the "Wireless debugging" toggle (adb_wifi_enabled) and pins the
 * ADB TCP port via persist./service. props, so it survives reboot.
 *
 * NOTE: this is a *different* file/path than any pre-existing
 * adb-static-port.sh you may already have. If you enable this and it's
 * persisting successfully, remove the old script to avoid two boot scripts
 * fighting over the port.
 */
object WirelessAdbController {

    private const val SCRIPT_NAME = "adb_wireless.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "adb_wireless_template.sh"

    const val DEFAULT_PORT = 5757

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** Reads the port number out of the persisted script, if present. */
    fun persistedPort(): Int? {
        val content = AssetInstaller.readFile(TARGET)
        val match = Regex("""tcp\.port\s+(\d+)""").find(content)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Current live ADB TCP port (service.adb.tcp.port), or null if unset/-1. */
    fun currentLivePort(): Int? {
        val out = RootShell.run("getprop service.adb.tcp.port").outString.trim()
        return out.toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * Current WLAN IPv4 address, read via `ip route get`. Only returns a
     * value if the default route is actually via a wlan*-named interface -
     * otherwise (e.g. on mobile data with WiFi off) returns null rather
     * than showing a cellular IP that's useless for `adb connect`.
     */
    fun currentWlanIp(): String? {
        val out = RootShell.run("ip route get 1.1.1.1 2>/dev/null").outString
        val dev = Regex("""dev (\S+)""").find(out)?.groupValues?.get(1)
        val src = Regex("""src (\S+)""").find(out)?.groupValues?.get(1)
        return if (dev?.startsWith("wlan") == true) src else null
    }

    /**
     * Writes the persisted script for [port]. If [applyLive] is true, also
     * enables wireless debugging and sets the port immediately.
     */
    fun setPort(context: Context, port: Int, applyLive: Boolean): ShellResult {
        val installResult = AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
            raw.replace("__PORT__", port.toString())
        }

        if (applyLive) {
            RootShell.run(
                "settings put global adb_wifi_enabled 1; " +
                    "setprop persist.adb.tcp.port $port; " +
                    "setprop service.adb.tcp.port $port"
            )
        }

        return installResult
    }

    /** Removes the persisted script and disables the live ADB TCP port. */
    fun disable(): ShellResult {
        val result = AssetInstaller.removeFile(TARGET)
        RootShell.run(
            "setprop service.adb.tcp.port -1; " +
                "settings put global adb_wifi_enabled 0"
        )
        return result
    }
}
