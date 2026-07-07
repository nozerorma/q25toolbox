package com.kgr.q25toolbox.modules

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.kgr.q25toolbox.core.RootShell

/** One UID's estimated share of battery use since last charge. */
data class AppPowerUsage(
    val uid: Int,
    val label: String,
    val packageName: String?,
    val mAh: Double,
    val percentOfTotal: Double,
    val isSystemApp: Boolean,
)

/**
 * Per-app battery usage estimation, sourced from the same underlying data as
 * Android's native "Battery usage" screen (BatteryStatsImpl's power model) -
 * but read directly via root instead of through Settings' own UI, which on
 * this device never populates because it additionally requires a
 * BATTERY_STATUS_FULL transition the charging driver never reports.
 *
 * Uses `--checkin` (a stable, machine-parseable format) rather than the
 * human-readable dump, whose formatting isn't meant to be scraped.
 */
object BatteryUsageController {

    private val PWI_UID_LINE = Regex("""^9,(-?\d+),l,pwi,uid,([0-9.eE+-]+),""")

    private val KNOWN_SYSTEM_UIDS = mapOf(
        0 to "Android (kernel/root)",
        1000 to "Android System",
        1001 to "Radio",
        1002 to "Bluetooth",
        1010 to "Wi-Fi",
        1013 to "Media",
        1041 to "Bluetooth stack",
        2000 to "Shell",
    )

    /**
     * Zeroes the underlying system battery stats. Needed on this device because the charging
     * driver never reports a full-charge signal, so Android's own auto-reset-on-full-charge
     * never fires and the numbers [readUsage] reads otherwise accumulate indefinitely across
     * multiple charge cycles instead of representing just the latest one.
     */
    fun resetStats() {
        RootShell.run("dumpsys batterystats --reset")
    }

    /** Reads and aggregates per-UID power estimates. Requires root; run off the main thread. */
    fun readUsage(context: Context): List<AppPowerUsage> {
        val output = RootShell.run("dumpsys batterystats --checkin").outString
        val perUid = mutableMapOf<Int, Double>()
        for (line in output.lineSequence()) {
            val match = PWI_UID_LINE.find(line) ?: continue
            val uid = match.groupValues[1].toIntOrNull() ?: continue
            val mah = match.groupValues[2].toDoubleOrNull() ?: continue
            perUid[uid] = (perUid[uid] ?: 0.0) + mah
        }

        val total = perUid.values.sum().takeIf { it > 0.0 } ?: return emptyList()
        val pm = context.packageManager

        return perUid.entries
            .filter { it.value > 0.0 }
            .map { (uid, mah) ->
                val (label, pkg, isSystem) = resolveUid(pm, uid)
                AppPowerUsage(uid, label, pkg, mah, mah / total * 100.0, isSystem)
            }
            .sortedByDescending { it.mAh }
    }

    /** Label, package name (if any), and whether the UID is a system component/app. */
    private fun resolveUid(pm: PackageManager, uid: Int): Triple<String, String?, Boolean> {
        val packages = try {
            pm.getPackagesForUid(uid)
        } catch (_: Exception) {
            null
        }
        val pkg = packages?.firstOrNull()
        if (pkg != null) {
            val appInfo = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (_: Exception) {
                null
            }
            val label = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkg
            val isSystem = appInfo?.let {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            } ?: true
            return Triple(label, pkg, isSystem)
        }
        // No package for this UID at all (kernel/hardware component) - always a system item.
        return Triple(KNOWN_SYSTEM_UIDS[uid] ?: "uid $uid", null, true)
    }
}
