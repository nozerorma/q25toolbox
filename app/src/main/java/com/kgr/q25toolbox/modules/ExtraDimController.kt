package com.kgr.q25toolbox.modules

import android.content.Context
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.core.ShellResult

object ExtraDimController {

    fun isActivated(): Boolean {
        val out = RootShell.run("settings get secure reduce_bright_colors_activated").outString.trim()
        return out == "1"
    }

    fun getDimmingLevel(): Int {
        val out = RootShell.run("settings get secure reduce_bright_colors_level").outString.trim()
        return out.toIntOrNull() ?: 50  // Default to 50% if not set
    }

    fun setActivated(activated: Boolean): ShellResult {
        val valStr = if (activated) "1" else "0"
        return RootShell.run("settings put secure reduce_bright_colors_activated $valStr")
    }

    fun setDimmingLevel(level: Int): ShellResult {
        val clamped = level.coerceIn(0, 100)
        return RootShell.run("settings put secure reduce_bright_colors_level $clamped")
    }

    // ------------------------------------------------------------- Schedule

    private const val SCHEDULE_SCRIPT_NAME = "extra_dim_schedule.sh"
    private const val SCHEDULE_TARGET = "/data/adb/service.d/$SCHEDULE_SCRIPT_NAME"
    private const val SCHEDULE_TEMPLATE_ASSET = "extra_dim_schedule_template.sh"
    private const val SCHEDULE_LOCK = "/data/adb/.extra_dim_schedule.lock"

    // Minutes since midnight (0..1439), so the schedule supports any time of day
    // (e.g. 00:35), not just whole hours.
    const val DEFAULT_START_MINUTES = 22 * 60
    const val DEFAULT_END_MINUTES = 7 * 60

    fun isScheduleEnabled(): Boolean = AssetInstaller.fileExists(SCHEDULE_TARGET)

    /** Whether the schedule watchdog daemon is currently running. */
    fun isScheduleRunning(): Boolean =
        RootShell.run("pgrep -f $SCHEDULE_SCRIPT_NAME >/dev/null 2>&1 && echo yes || echo no")
            .outString.trim() == "yes"

    fun persistedStartMinutes(): Int {
        val content = AssetInstaller.readFile(SCHEDULE_TARGET)
        return Regex("""START_MINUTES=(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull()
            ?: DEFAULT_START_MINUTES
    }

    fun persistedEndMinutes(): Int {
        val content = AssetInstaller.readFile(SCHEDULE_TARGET)
        return Regex("""END_MINUTES=(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull()
            ?: DEFAULT_END_MINUTES
    }

    /**
     * Enables (installs + launches) or disables (stops + removes) the schedule
     * watchdog, which turns Extra Dim on/off at [startMinutes]/[endMinutes] (each
     * minutes-since-midnight) every day. Any running instance is stopped first so
     * a changed window takes effect immediately.
     */
    fun setScheduleEnabled(context: Context, enabled: Boolean, startMinutes: Int, endMinutes: Int): ShellResult {
        RootShell.run("pkill -f $SCHEDULE_SCRIPT_NAME 2>/dev/null; rm -f $SCHEDULE_LOCK")

        return if (enabled) {
            val result = AssetInstaller.installFromAsset(context, SCHEDULE_TEMPLATE_ASSET, SCHEDULE_TARGET) { raw ->
                raw.replace("__START_MINUTES__", startMinutes.toString())
                   .replace("__END_MINUTES__", endMinutes.toString())
            }
            RootShell.run("nohup sh $SCHEDULE_TARGET >/dev/null 2>&1 &")
            result
        } else {
            AssetInstaller.removeFile(SCHEDULE_TARGET)
        }
    }
}
