package com.kgr.q25toolbox.modules

import android.content.Context
import android.media.AudioManager
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.core.ShellResult

/**
 * Toggles MediaTek's BesLoudness speaker loudness-enhancement DSP stage.
 *
 * The vendor persist prop `persist.vendor.audiohal.besloudness_state`
 * (referenced by /vendor/etc/audio_param/SoundEnhancement_AudioParam.xml)
 * looked like the control this app should write, but setprop-ing it did
 * nothing audible - confirmed live on device via logcat while watching the
 * stock Settings "Sound Enhancement" toggle: it calls
 * `AudioManager.setParameters("SetBesLoudnessStatus=0/1")`, which the HAL
 * (AudioALSAStreamManager) applies immediately to the mtk_bessound DSP lib -
 * no restart needed - and separately persists into that same XML file via
 * `setBesLoudnessStateToXML()`. A freshly-opened stream (different PID in the
 * log) then reads it straight back out of the XML on its own. So the actual
 * control surface is that AudioManager call, not the persist prop, which
 * this app never needed to touch at all.
 *
 * [applyLive] is called both from here (manual toggle, in-process) and from
 * [BesLoudnessReceiver] (the root schedule daemon below, which is a plain
 * shell script and can't call Android APIs directly, so it asks the app to
 * do it via `am broadcast`).
 */
object BesLoudnessController {

    private const val KEY_SET = "SetBesLoudnessStatus"
    private const val KEY_GET = "GetBesLoudnessStatus"

    fun isEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val raw = am.getParameters(KEY_GET) ?: return false
        return raw.substringAfter('=', raw).trim() == "1"
    }

    fun applyLive(context: Context, enabled: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setParameters("$KEY_SET=${if (enabled) 1 else 0}")
    }

    fun setEnabled(context: Context, enabled: Boolean) = applyLive(context, enabled)

    // ------------------------------------------------------------- Schedule

    private const val SCHEDULE_SCRIPT_NAME = "besloudness_schedule.sh"
    private const val SCHEDULE_TARGET = "/data/adb/service.d/$SCHEDULE_SCRIPT_NAME"
    private const val SCHEDULE_TEMPLATE_ASSET = "besloudness_schedule_template.sh"
    private const val SCHEDULE_LOCK = "/data/adb/.besloudness_schedule.lock"

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
     * Whether the installed daemon is both alive AND running the script we'd install
     * today - see [AssetInstaller.matchesAsset] for why a bare "is it running" check
     * isn't enough (a stale script loops forever too, silently enforcing nothing).
     */
    fun isScheduleHealthy(context: Context, startMinutes: Int, endMinutes: Int): Boolean =
        isScheduleRunning() && AssetInstaller.matchesAsset(context, SCHEDULE_TEMPLATE_ASSET, SCHEDULE_TARGET) { raw ->
            raw.replace("__START_MINUTES__", startMinutes.toString())
                .replace("__END_MINUTES__", endMinutes.toString())
        }

    /**
     * Enables (installs + launches) or disables (stops + removes) the schedule
     * watchdog, which turns BesLoudness on/off at [startMinutes]/[endMinutes] (each
     * minutes-since-midnight) every day. Any running instance is stopped first so
     * a changed window takes effect immediately.
     */
    fun setScheduleEnabled(context: Context, enabled: Boolean, startMinutes: Int, endMinutes: Int): ShellResult {
        // "pkill -f" was found unreliable on this device's toybox build - see
        // ExtraDimController for the same fix and why it was needed.
        RootShell.run("kill \$(pgrep -f $SCHEDULE_SCRIPT_NAME) 2>/dev/null; rm -f $SCHEDULE_LOCK")

        return if (enabled) {
            val result = AssetInstaller.installFromAsset(context, SCHEDULE_TEMPLATE_ASSET, SCHEDULE_TARGET) { raw ->
                raw.replace("__START_MINUTES__", startMinutes.toString())
                   .replace("__END_MINUTES__", endMinutes.toString())
            }
            // setsid detaches the daemon into its own session so it doesn't get
            // dragged down when the invoking root shell (a transient libsu
            // session, not a real login shell) is later recycled or torn down -
            // same fix as ExtraDimController's schedule daemon.
            RootShell.run("nohup setsid sh $SCHEDULE_TARGET </dev/null >/dev/null 2>&1 &")
            result
        } else {
            AssetInstaller.removeFile(SCHEDULE_TARGET)
        }
    }
}
