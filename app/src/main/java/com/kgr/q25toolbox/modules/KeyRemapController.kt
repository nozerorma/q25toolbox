package com.kgr.q25toolbox.modules

import android.content.SharedPreferences
import android.util.Log
import com.kgr.q25toolbox.core.RootShell

/**
 * Hardware-level key remapping for the Q25 keyboard.
 *
 * It overrides the system's keylayout file `/system/usr/keylayout/Q25_keyboard.kl`
 * using a bind mount from `/data/local/tmp/Q25_keyboard.kl` in the master mount
 * namespace (`su -M`).
 *
 * Live reload is achieved by unbinding and rebinding the Q25_keyboard i2c driver.
 * Persistence across boots is managed via `/data/adb/service.d/key_remap.sh`.
 */
object KeyRemapController {

    const val KEY_REMAP_ENABLED = "key_remap_enabled"
    const val KEY_REMAP_SOURCE  = "key_remap_source"

    private const val BOOT_SCRIPT = "/data/adb/service.d/key_remap.sh"
    private const val TMP_FILE = "/data/local/tmp/Q25_keyboard.kl"
    private const val SYS_FILE = "/system/usr/keylayout/Q25_keyboard.kl"

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
            // Clean up the mount and reload the driver to restore defaults
            RootShell.run(
                "su -M -c 'echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/unbind" +
                " ; sleep 1" +
                " ; umount -l $SYS_FILE" +
                " ; echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/bind" +
                " ; rm -f $TMP_FILE'"
            )
            Log.d("KeyRemapController", "Cleared remaps and restored defaults")
        }
    }

    private fun generateBootScript(source: SourceKey): String {
        // The column padding between scancode and keycode in the layout file varies with the
        // scancode's digit width (e.g. "54    SHIFT_RIGHT" vs "580   APP_SWITCH"), so match on
        // one-or-more whitespace rather than a fixed run of spaces.
        val sedCommand = "sed -E \"s/key ${source.scancode}[[:space:]]+${source.originalKeycode}/key ${source.scancode} CTRL_RIGHT/\" $SYS_FILE > $TMP_FILE.tmp"
        return """
#!/system/bin/sh
# Wait for the system partition layout file to be available
while [ ! -f $SYS_FILE ]; do
  sleep 1
done

# Force unmount any stale mounts from previous boots/sessions
umount -l $SYS_FILE 2>/dev/null

# Clean up tmp and copy original
rm -f $TMP_FILE
cp $SYS_FILE $TMP_FILE

# Remap the target key
$sedCommand
cat $TMP_FILE.tmp > $TMP_FILE
rm -f $TMP_FILE.tmp

# Set the SELinux label to system_file so system_server/EventHub can read it
chcon u:object_r:system_file:s0 $TMP_FILE

# Bind mount the modified keylayout system-wide
mount --bind $TMP_FILE $SYS_FILE

# Reload the driver if we are already booted (handles live apply)
if [ -d /sys/bus/i2c/drivers/Q25_keyboard ]; then
  echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/unbind
  sleep 1
  echo 6-001f > /sys/bus/i2c/drivers/Q25_keyboard/bind
fi
        """.trimIndent()
    }
}
