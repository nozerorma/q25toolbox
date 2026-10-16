package com.kgr.q25toolbox.modules

import android.content.SharedPreferences
import android.util.Log
import com.kgr.q25toolbox.core.RomProfile
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.core.ShellResult

/**
 * Hardware-level key remapping for the Q25 keyboard.
 *
 * It overrides the keylayout file `Q25_keyboard.kl` using a bind mount from
 * `/data/local/tmp/Q25_keyboard.kl` in the master mount namespace (`su -M`).
 *
 * The file's actual partition differs by ROM: stock BenOS ships it under
 * `/system/usr/keylayout/`, while a Treble-compliant build (e.g. the
 * unofficial LineageOS port, device tree `device/xelex/Q25`) ships it under
 * `/vendor/usr/keylayout/` instead - confirmed via that device tree's
 * `device.mk` (`PRODUCT_COPY_FILES` targets `$(TARGET_COPY_OUT_VENDOR)`).
 * Rather than hardcode one and silently no-op on the other (the original bug:
 * always targeting `/system`, so the boot script's "wait for the file" loop
 * spun forever on a Lineage build), every script here probes both at runtime
 * and uses whichever actually exists - including its matching SELinux label
 * (`system_file` vs `vendor_keylayout_file`; plain `vendor_file` is denied to system_server, verified
 * on LineageOS 23 where EventHub then silently falls back to Generic.kl), since the wrong one could make the
 * bind-mounted copy unreadable by system_server/EventHub under enforcing.
 *
 * Live reload is achieved by unbinding and rebinding the Q25_keyboard i2c driver.
 * Persistence across boots is managed via `/data/adb/service.d/key_remap.sh`.
 */
object KeyRemapController {

    const val KEY_REMAP_ENABLED = "key_remap_enabled"
    const val KEY_REMAP_SOURCE  = "key_remap_source"

    /**
     * The system always opens its own Overview on the physical Recents key (APP_SWITCH, scancode 580),
     * even when an accessibility service consumes the key (verified on LineageOS 23: the launcher resumes
     * before our overlay is up). So while a Recents overlay mode is selected, that scancode is remapped to
     * [RECENTS_TRIGGER_KEYCODE], which Android attaches no behaviour to; the accessibility service then
     * treats it as the "open Recents overlay" key.
     */
    const val KEY_RECENTS_OVERLAY_REMAP = "recents_overlay_key_remap"
    const val RECENTS_TRIGGER_KEYCODE = "PROG_RED"

    /**
     * "Leave the Recents key to another app" (Key Mapper). The scancode stays remapped to [RECENTS_TRIGGER_KEYCODE] so
     * the system does nothing with it, but our service no longer acts on the key: the other app sees a plain
     * PROG_RED (keycode 183, scancode 580) and decides what it does. It can ask us to open our overlay through
     * [com.kgr.q25toolbox.service.RunActionReceiver]. LineageOS only (the setting is ignored elsewhere): BenOS keeps its
     * behaviour.
     */
    const val KEY_RECENTS_KEY_EXTERNAL = "recents_key_external"
    private const val RECENTS_SCANCODE = 580

    private const val BOOT_SCRIPT = "/data/adb/service.d/key_remap.sh"
    private const val TMP_FILE = "/data/local/tmp/Q25_keyboard.kl"
    private const val SYSTEM_FILE = "/system/usr/keylayout/Q25_keyboard.kl"
    private const val VENDOR_FILE = "/vendor/usr/keylayout/Q25_keyboard.kl"

    /**
     * The Q25 Trackpad Customizer's root helper EVIOCGRABs the physical keyboard and re-emits its events
     * through uinput as a virtual "Custom Q25 Keyboard" (name [VKBD_NAME]). EventHub lays that device out
     * with the Generic.kl fallback, so while the customizer runs, the Q25_keyboard.kl override above never
     * sees any event: the same remaps are therefore also overlaid on a copy of Generic.kl. Safe on this
     * hardware: no other device that falls back to Generic.kl emits scancodes 41/54/580 (verified with
     * getevent: mtk-kpd = MUTE/VOLUMEDOWN, mtk-pmic-keys = VOLUMEUP/POWER, headset jack = volume/media keys),
     * so only the customizer's virtual keyboard is affected. When the customizer is absent the overlay is
     * inert: the physical keyboard alone emits those scancodes, and it keeps its own Q25_keyboard.kl remap.
     */
    private const val VKBD_NAME = "Custom Q25 Keyboard"
    private const val GENERIC_FILE = "/system/usr/keylayout/Generic.kl"
    private const val TMP_GENERIC = "/data/local/tmp/Q25_Generic.kl"
    private const val LOCK_FILE = "/data/adb/.key_remap.lock"

    /**
     * Resolves to whichever of [SYSTEM_FILE] / [VENDOR_FILE] exists on this device, setting
     * $SYS_FILE and $SELABEL for the rest of the script to use. Waits (rather than failing
     * outright) since this can run at boot before either partition's overlay is mounted yet.
     */
    private val resolveSysFile = """
        while [ ! -f $SYSTEM_FILE ] && [ ! -f $VENDOR_FILE ]; do
          sleep 1
        done
        if [ -f $SYSTEM_FILE ]; then
          SYS_FILE=$SYSTEM_FILE
          SELABEL=u:object_r:system_file:s0
        else
          SYS_FILE=$VENDOR_FILE
          SELABEL=u:object_r:vendor_keylayout_file:s0
        fi
    """.trimIndent()

    enum class SourceKey(
        val label: String,
        val description: String,
        val scancode: Int,
        val originalKeycode: String
    ) {
        GRAVE(
            "Currency key",
            "The € / £ / $ key next to Space. Currently mismapped as backtick by the firmware — " +
            "remapping it to Ctrl doesn't affect normal typing.",
            41,
            "GRAVE"
        ),
        RSHIFT(
            "Right Shift",
            "The right-hand Shift key. Left Shift still works normally for uppercase.",
            54,
            "SHIFT_RIGHT"
        ),
        RECENTS(
            "Recents (BlackBerry key)",
            "The dedicated recent-apps/task-switcher key. Remapping it to Ctrl means you lose " +
            "the hardware shortcut for recents.",
            580,
            "APP_SWITCH"
        )
    }

    fun isEnabled(prefs: SharedPreferences) =
        prefs.getBoolean(KEY_REMAP_ENABLED, false)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) =
        prefs.edit().putBoolean(KEY_REMAP_ENABLED, enabled).apply()

    fun isRecentsOverlayRemap(prefs: SharedPreferences) =
        prefs.getBoolean(KEY_RECENTS_OVERLAY_REMAP, false)

    fun isRecentsKeyExternal(prefs: SharedPreferences) = prefs.getBoolean(KEY_RECENTS_KEY_EXTERNAL, false)

    fun setRecentsKeyExternal(prefs: SharedPreferences, on: Boolean) =
        prefs.edit().putBoolean(KEY_RECENTS_KEY_EXTERNAL, on).apply()

    /**
     * Whether scancode 580 must be remapped to the trigger keycode: when an overlay Recents mode needs it or the key is
     * left to another app, except if the user chose the Recents key itself as their Ctrl source (then it is Ctrl).
     * Pure, for tests.
     */
    internal fun wantsRecentsRemap(overlayRemap: Boolean, external: Boolean, ctrlOnRecents: Boolean) =
        (overlayRemap || external) && !ctrlOnRecents

    fun setRecentsOverlayRemap(prefs: SharedPreferences, on: Boolean) =
        prefs.edit().putBoolean(KEY_RECENTS_OVERLAY_REMAP, on).apply()

    fun getSourceKey(prefs: SharedPreferences): SourceKey {
        val raw = prefs.getString(KEY_REMAP_SOURCE, SourceKey.GRAVE.name) ?: SourceKey.GRAVE.name
        return try { SourceKey.valueOf(raw) } catch (_: Exception) { SourceKey.GRAVE }
    }

    fun setSourceKey(prefs: SharedPreferences, key: SourceKey) =
        prefs.edit().putString(KEY_REMAP_SOURCE, key.name).apply()

    /**
     * Shell snippet that makes EventHub re-open input devices (and re-parse their keylayouts) WITHOUT
     * touching the i2c driver: a uevent remove/add on the input node makes ueventd delete and
     * recreate /dev/input/eventN, which EventHub sees via inotify. Same idea as Key2Toolbox's
     * `reloadInputDevice`. Used where unbinding the driver can panic the kernel
     * ([RomProfile.keyboardRebindUnsafe]); verified live on LineageOS 23.
     *
     * Reloads BOTH keyboards: the physical Q25_keyboard (so its layout is re-parsed) and the
     * customizer's virtual [VKBD_NAME] (which lays out with Generic.kl, so the overlay on it must be
     * re-read too). A virtual keyboard that appears LATER reads the overlaid Generic.kl at its
     * EventHub registration, so the customizer restarting needs no action.
     *
     * The loop runs in a subshell that ignores TERM/HUP: a new [applySettings] kills the previous daemon
     * (`pgrep -f key_remap.sh`), and without this a kill landing between `remove` and `add` left the
     * keyboard unregistered (seen in a user log: gone for 4.4 s).
     */
    private const val UEVENT_RELOAD =
        "( trap \"\" TERM HUP; for d in /sys/class/input/event*; do " +
            "N=\$(cat \"\$d/device/name\" 2>/dev/null); " +
            "if [ \"\$N\" = \"Q25_keyboard\" ] || [ \"\$N\" = \"$VKBD_NAME\" ]; then " +
            "echo remove > \"\$d/uevent\"; sleep 1; echo add > \"\$d/uevent\"; fi; done )"

    /** Same as [UEVENT_RELOAD] but for the virtual keyboard only, used on the BenOS path where the
     * physical one is reloaded by re-binding its driver instead (the uinput device has no driver to bind). */
    private const val UEVENT_RELOAD_VKBD =
        "( trap \"\" TERM HUP; for d in /sys/class/input/event*; do " +
            "if [ \"\$(cat \"\$d/device/name\" 2>/dev/null)\" = \"$VKBD_NAME\" ]; then " +
            "echo remove > \"\$d/uevent\"; sleep 1; echo add > \"\$d/uevent\"; fi; done )"

    /**
     * Unmounts every bind stacked on the layout paths inside the mount namespace of [pidExpr] (a shell expression).
     * Each apply used to stack one more bind, and one umount only peels the top one. `--` is required: toybox's
     * nsenter otherwise parses `umount -l` as its own options and fails ("Unknown option 'l'"), which made the
     * old cleanup a silent no-op. Capped, so it cannot spin.
     */
    private fun unmountAllIn(pidExpr: String) =
        "for f in $SYSTEM_FILE $VENDOR_FILE $GENERIC_FILE; do i=0; " +
            "while [ \$i -lt 8 ] && nsenter -t $pidExpr -m -- umount -l \$f 2>/dev/null; do i=\$((i+1)); done; done"

    /**
     * Single-instance gate for the boot script, inserted at the top of the generated script. Two copies
     * running at once (an app re-apply racing the boot script) once interleaved each other's
     * unmount/mount/reload cycles and left the keyboard on the stale pre-remap layout. A fresh copy
     * waits for a live predecessor to exit (applySettings kills it first) before taking over the lock.
     * Built as a normal string: raw strings do not process the \$ escapes.
     */
    private const val LOCK_BLOCK =
        "LOCK=$LOCK_FILE\n" +
        "i=0\n" +
        "while [ \$i -lt 15 ]; do\n" +
        "  OLD_PID=\$(cat \"\$LOCK\" 2>/dev/null)\n" +
        "  if [ -n \"\$OLD_PID\" ] && grep -q key_remap \"/proc/\$OLD_PID/cmdline\" 2>/dev/null; then\n" +
        "    sleep 1\n" +
        "    i=\$((i+1))\n" +
        "  else\n" +
        "    break\n" +
        "  fi\n" +
        "done\n" +
        "echo \$\$ > \"\$LOCK\"\n"

    /**
     * Apply the remapping settings live and update the boot script. Serialized: two applies in flight used to
     * race (the second killed the first mid-reload), which a quick succession of toggles triggers.
     */
    @Synchronized
    fun applySettings(prefs: SharedPreferences) {
        val ctrlEnabled = isEnabled(prefs)
        val source = getSourceKey(prefs)
        val rebind = !RomProfile.keyboardRebindUnsafe()
        // If the user chose the Recents key itself as their Ctrl source, it is Ctrl, not an overlay trigger.
        val recentsRemap = wantsRecentsRemap(isRecentsOverlayRemap(prefs), isRecentsKeyExternal(prefs) && RomProfile.autoDetectedLineage(), ctrlEnabled && source == SourceKey.RECENTS)
        val enabled = ctrlEnabled || recentsRemap

        if (enabled) {
            val script = generateBootScript(if (ctrlEnabled) source else null, rebind, recentsRemap)
            // Stop any existing remap daemon first
            RootShell.run("kill \$(pgrep -f key_remap.sh) 2>/dev/null")
            // Unmount any global/master-namespace mount so all regular apps have a clean mountinfo
            RootShell.run("su -M -c 'umount -l $SYSTEM_FILE 2>/dev/null ; umount -l $VENDOR_FILE 2>/dev/null ; umount -l $GENERIC_FILE 2>/dev/null'")
            // Write script to /data/adb/service.d/
            RootShell.run("cat << 'EOF' > $BOOT_SCRIPT\n$script\nEOF\nchmod 755 $BOOT_SCRIPT")
            // Execute the script live in the background
            RootShell.run("sh $BOOT_SCRIPT &")
            Log.d("KeyRemapController", "Applied remap for ${source.name} and saved boot script (rebind=$rebind)")
            logVerification()
        } else {
            // Stop daemon and delete boot script
            RootShell.run("kill \$(pgrep -f key_remap.sh) 2>/dev/null; rm -f $BOOT_SCRIPT")
            // Clean up the mount and reload the keyboard to restore defaults. Unmounts both
            // candidate paths unconditionally (whichever wasn't ever bind-mounted just no-ops)
            // rather than re-resolving which one applies - simpler and just as safe for cleanup.
            val reload = if (rebind) {
                "echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/unbind ; sleep 1 ; "
            } else ""
            val reattach = if (rebind) " ; echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/bind" else " ; $UEVENT_RELOAD"
            RootShell.run(
                "su -M -c '$reload" +
                "umount -l $SYSTEM_FILE 2>/dev/null" +
                " ; umount -l $VENDOR_FILE 2>/dev/null" +
                " ; umount -l $GENERIC_FILE 2>/dev/null" +
                " ; SS_PID=\$(pidof system_server)" +
                " ; if [ -n \"\$SS_PID\" ]; then ${unmountAllIn("\$SS_PID")}; fi" +
                "$reattach" +
                " ; rm -f $TMP_FILE ; rm -f $TMP_GENERIC'"
            )
            Log.d("KeyRemapController", "Cleared remaps and restored defaults (rebind=$rebind)")
            logVerification()
        }
    }

    /**
     * Logs what system_server (hence EventHub) actually sees: how many binds sit on the layout and the
     * remappable lines. Waits for the background script's reload to finish (~3 s), which also keeps a
     * following apply from killing it. This is the evidence a debug log needs to tell "not applied"
     * from "applied but ineffective".
     */
    private fun logVerification() {
        val r = RootShell.run(
            "sleep 3; SS=\$(pidof system_server); " +
                "echo \"binds=\$(grep -c keylayout /proc/\$SS/mountinfo)\"; " +
                "for f in $SYSTEM_FILE $VENDOR_FILE $GENERIC_FILE; do nsenter -t \$SS -m -- grep -E '^key (41|54|580)[[:space:]]' \$f 2>/dev/null; done; " +
                "grep -c Q25_keyboard /proc/bus/input/devices"
        )
        Log.d("KeyRemapController", "verify (system_server view): ${r.out.joinToString(" | ")}")
    }

    /**
     * Unbinds and rebinds the Q25_keyboard i2c driver without touching any keylayout
     * remap/mount - a plain "power cycle the driver" recovery action, for when the physical
     * keyboard stops responding (observed after the phone's proximity sensor gets stuck
     * during/after a call - see Q25AccessibilityService's call-screen recovery). Any active
     * remap's bind mount is untouched by an unbind/rebind (it's a VFS construct independent
     * of the i2c driver being bound), so this is safe to call regardless of whether Key
     * Remap is enabled.
     */
    fun respawnKeyboard(): ShellResult =
        if (RomProfile.keyboardRebindUnsafe()) {
            ShellResult(false, listOf("driver rebind disabled: it can panic the kernel on this ROM"))
        } else respawnKeyboardUnsafe()

    private fun respawnKeyboardUnsafe(): ShellResult = RootShell.run(
        "su -M -c 'echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/unbind" +
        " ; sleep 1" +
        " ; echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/bind'"
    )

    /**
     * [rebind] = true (BenOS, unchanged behaviour): unbind the i2c driver before swapping the mount
     * and rebind after, so EventHub reloads the layout live. false (Lineage, see
     * [RomProfile.keyboardRebindUnsafe]): mount, then reload through a uevent remove/add instead.
     */
    internal fun generateBootScript(source: SourceKey?, rebind: Boolean, recentsRemap: Boolean = false): String {
        // $sysFileVar/$selabelVar are shell-side references to the variables resolveSysFile
        // sets, not Kotlin ones - built as plain strings so they paste into the script as
        // literal `$SYS_FILE`/`$SELABEL` for the shell to expand at runtime.
        val sysFileVar = "\$SYS_FILE"
        val selabelVar = "\$SELABEL"
        val targetPidVar = "\$target_pid"
        val ssPidVar = "\$SS_PID"
        val curPidVar = "\$CUR_PID"
        // The column padding between scancode and keycode in the layout file varies with the
        // scancode's digit width (e.g. "54    SHIFT_RIGHT" vs "580   APP_SWITCH"), so match on
        // one-or-more whitespace rather than a fixed run of spaces.
        // One sed pass applies every active remap. Ctrl source key (optional) and the Recents-overlay trigger.
        val exprs = buildList {
            if (source != null) add("s/key ${source.scancode}[[:space:]]+${source.originalKeycode}/key ${source.scancode} CTRL_RIGHT/")
            if (recentsRemap) add("s/key $RECENTS_SCANCODE[[:space:]]+APP_SWITCH/key $RECENTS_SCANCODE $RECENTS_TRIGGER_KEYCODE/")
        }
        val sedCommand = "sed -E ${exprs.joinToString(" ") { "-e \"$it\"" }} $sysFileVar > $TMP_FILE.tmp"
        val sedGenericCommand = "sed -E ${exprs.joinToString(" ") { "-e \"$it\"" }} $GENERIC_FILE > $TMP_GENERIC.tmp"
        val driver = "/sys/bus/i2c/drivers/Q25_keyboard"
        val unbindBlock = if (!rebind) "" else """
# Unbind the driver FIRST if it's already bound (e.g. switching source key while
# remap is enabled, not a fresh boot). This closes EventHub's file descriptor on
# the currently bind-mounted layout file, which is required before the unmount
# below can actually take effect - otherwise it stays lazily "unmounted but
# still open", and the cp right after would read through the stale mount (or a
# deleted-file dangling reference once rm -f runs), corrupting the copy instead
# of getting the pristine original.
if [ -d $driver ]; then
  echo 6-001f > $driver/unbind
  sleep 1
fi
"""
        val bindBlock = if (!rebind) "\n# Reload via uevent (no driver unbind, see UEVENT_RELOAD)\n$UEVENT_RELOAD\n" else """
# Rebind the driver so EventHub reloads the (now modified) keylayout
if [ -d $driver ]; then
  echo 6-001f > $driver/bind
fi
# The customizer's virtual keyboard has no driver to rebind: reload it via uevent.
$UEVENT_RELOAD_VKBD
"""
        val bailRebind = if (!rebind) "" else "\n  if [ -d $driver ]; then echo 6-001f > $driver/bind; fi"
        return """
#!/system/bin/sh
# Single instance only: see the LOCK_BLOCK doc above.
$LOCK_BLOCK
# Resolve which partition actually has the layout file on this ROM (also waits for it
# to be available - this can run at boot before either overlay is mounted yet).
$resolveSysFile
$unbindBlock
# Now safe to fully release any stale global mount so master/app namespaces stay clean
umount -l $sysFileVar 2>/dev/null

# Clean up tmp and copy the now-guaranteed-original file
rm -f $TMP_FILE
cp $sysFileVar $TMP_FILE

# Remap the target key
$sedCommand
cat $TMP_FILE.tmp > $TMP_FILE
rm -f $TMP_FILE.tmp

# Safety: this script re-runs every boot, so an empty result (failed cp/sed) must never
# be mounted - that would leave the keyboard dead on every boot. Only emptiness is checked,
# not content, so a valid layout on any ROM is never rejected.
if [ ! -s $TMP_FILE ]; then
  rm -f $TMP_FILE$bailRebind
  exit 1
fi

# Label it to match its target partition (system_file or vendor_file) so
# system_server/EventHub can read it under enforcing.
chcon $selabelVar $TMP_FILE
chmod 644 $TMP_FILE

# The customizer overlay: while the Q25 Trackpad Customizer's helper grabs the physical
# keyboard and re-emits through uinput (see the VKBD_NAME doc), its virtual keyboard is
# laid out with Generic.kl, so the same remaps are overlaid there too. Built the same way:
# copy from the pristine original, sed, and the same never-mount-an-empty-file safety.
umount -l $GENERIC_FILE 2>/dev/null
rm -f $TMP_GENERIC
cp $GENERIC_FILE $TMP_GENERIC
$sedGenericCommand
cat $TMP_GENERIC.tmp > $TMP_GENERIC
rm -f $TMP_GENERIC.tmp
if [ ! -s $TMP_GENERIC ]; then
  rm -f $TMP_GENERIC$bailRebind
  exit 1
fi
chcon u:object_r:system_file:s0 $TMP_GENERIC
chmod 644 $TMP_GENERIC

# Mount ONLY inside system_server's mount namespace so EventHub sees it,
# but untrusted apps, root detectors, and banking apps have a completely clean /proc/self/mountinfo.
apply_to_system_server() {
  target_pid=$1
  # Drop binds left by earlier applies first (they stack), then bind the fresh copy.
  ${unmountAllIn(targetPidVar)}
  nsenter -t $targetPidVar -m -- mount --make-slave /vendor 2>/dev/null
  nsenter -t $targetPidVar -m -- mount --make-slave /system 2>/dev/null
  nsenter -t $targetPidVar -m -- mount --bind $TMP_FILE $sysFileVar
  nsenter -t $targetPidVar -m -- mount --bind $TMP_GENERIC $GENERIC_FILE
}

# Wait for system_server
while [ -z "$(pidof system_server)" ]; do
  sleep 1
done
SS_PID=$(pidof system_server)
apply_to_system_server $ssPidVar
$bindBlock

# Watchdog loop: if system_server ever crashes or restarts (soft reboot),
# re-apply the bind mount into the new system_server's namespace.
while true; do
  sleep 3
  CUR_PID=$(pidof system_server)
  if [ -n "$curPidVar" ] && [ "$curPidVar" != "$ssPidVar" ]; then
    sleep 1
    apply_to_system_server $curPidVar
    $bindBlock
    SS_PID=$curPidVar
  fi
done
        """.trimIndent()
    }
}
