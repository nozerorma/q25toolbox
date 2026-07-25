package com.kgr.q25toolbox.modules

import android.content.SharedPreferences
import android.util.Log
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
 * (`system_file` vs `vendor_file`), since the wrong one could make the
 * bind-mounted copy unreadable by system_server/EventHub under enforcing.
 *
 * Live reload is achieved by unbinding and rebinding the Q25_keyboard i2c driver.
 * Persistence across boots is managed via `/data/adb/service.d/key_remap.sh`.
 */
object KeyRemapController {

    const val KEY_REMAP_ENABLED = "key_remap_enabled"
    const val KEY_REMAP_SOURCE  = "key_remap_source"

    private const val BOOT_SCRIPT = "/data/adb/service.d/key_remap.sh"
    private const val TMP_FILE = "/data/local/tmp/Q25_keyboard.kl"
    private const val SYSTEM_FILE = "/system/usr/keylayout/Q25_keyboard.kl"
    private const val VENDOR_FILE = "/vendor/usr/keylayout/Q25_keyboard.kl"

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
          SELABEL=u:object_r:vendor_file:s0
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

    fun getSourceKey(prefs: SharedPreferences): SourceKey {
        val raw = prefs.getString(KEY_REMAP_SOURCE, SourceKey.GRAVE.name) ?: SourceKey.GRAVE.name
        return try { SourceKey.valueOf(raw) } catch (_: Exception) { SourceKey.GRAVE }
    }

    fun setSourceKey(prefs: SharedPreferences, key: SourceKey) =
        prefs.edit().putString(KEY_REMAP_SOURCE, key.name).apply()

    /** Apply the remapping settings live and update the boot script. */
    fun applySettings(prefs: SharedPreferences) {
        val enabled = isEnabled(prefs)
        val source = getSourceKey(prefs)

        if (enabled) {
            val script = generateBootScript(source)
            // Write script to /data/adb/service.d/
            RootShell.run("cat << 'EOF' > $BOOT_SCRIPT\n$script\nEOF\nchmod 755 $BOOT_SCRIPT")
            // Execute the script live using mount-master namespace
            RootShell.run("su -M -c '$BOOT_SCRIPT'")
            Log.d("KeyRemapController", "Applied remap for ${source.name} and saved boot script")
        } else {
            // Delete boot script
            RootShell.run("rm -f $BOOT_SCRIPT")
            // Clean up the mount and reload the driver to restore defaults. Unmounts both
            // candidate paths unconditionally (whichever wasn't ever bind-mounted just no-ops)
            // rather than re-resolving which one applies - simpler and just as safe for cleanup.
            RootShell.run(
                "su -M -c 'echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/unbind" +
                " ; sleep 1" +
                " ; umount -l $SYSTEM_FILE 2>/dev/null" +
                " ; umount -l $VENDOR_FILE 2>/dev/null" +
                " ; echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/bind" +
                " ; rm -f $TMP_FILE'"
            )
            Log.d("KeyRemapController", "Cleared remaps and restored defaults")
        }
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
    fun respawnKeyboard(): ShellResult = RootShell.run(
        "su -M -c 'echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/unbind" +
        " ; sleep 1" +
        " ; echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/bind'"
    )

    private fun generateBootScript(source: SourceKey): String {
        // $sysFileVar/$selabelVar are shell-side references to the variables resolveSysFile
        // sets, not Kotlin ones - built as plain strings so they paste into the script as
        // literal `$SYS_FILE`/`$SELABEL` for the shell to expand at runtime.
        val sysFileVar = "\$SYS_FILE"
        val selabelVar = "\$SELABEL"
        // The column padding between scancode and keycode in the layout file varies with the
        // scancode's digit width (e.g. "54    SHIFT_RIGHT" vs "580   APP_SWITCH"), so match on
        // one-or-more whitespace rather than a fixed run of spaces.
        val sedCommand =
            "sed -E \"s/key ${source.scancode}[[:space:]]+${source.originalKeycode}/key ${source.scancode} CTRL_RIGHT/\" $sysFileVar > $TMP_FILE.tmp"
        return """
#!/system/bin/sh
# Resolve which partition actually has the layout file on this ROM (also waits for it
# to be available - this can run at boot before either overlay is mounted yet).
$resolveSysFile

# Unbind the driver FIRST if it's already bound (e.g. switching source key while
# remap is enabled, not a fresh boot). This closes EventHub's file descriptor on
# the currently bind-mounted layout file, which is required before the unmount
# below can actually take effect - otherwise it stays lazily "unmounted but
# still open", and the cp right after would read through the stale mount (or a
# deleted-file dangling reference once rm -f runs), corrupting the copy instead
# of getting the pristine original.
if [ -d /sys/bus/i2c/drivers/Q25_keyboard ]; then
  echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/unbind
  sleep 1
fi

# Now safe to fully release any stale mount from a previous boot/session
umount -l $sysFileVar 2>/dev/null

# Clean up tmp and copy the now-guaranteed-original file
rm -f $TMP_FILE
cp $sysFileVar $TMP_FILE

# Remap the target key
$sedCommand
cat $TMP_FILE.tmp > $TMP_FILE
rm -f $TMP_FILE.tmp

# Label it to match its target partition (system_file or vendor_file) so
# system_server/EventHub can read it under enforcing.
chcon $selabelVar $TMP_FILE

# Bind mount the modified keylayout over the original
mount --bind $TMP_FILE $sysFileVar

# Rebind the driver so EventHub reloads the (now modified) keylayout
if [ -d /sys/bus/i2c/drivers/Q25_keyboard ]; then
  echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/bind
fi
        """.trimIndent()
    }
}
