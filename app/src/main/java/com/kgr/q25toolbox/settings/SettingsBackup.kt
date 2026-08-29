package com.kgr.q25toolbox.settings

import android.content.Context
import android.net.Uri
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.AutoFocusController
import com.kgr.q25toolbox.modules.BatteryUsageController
import com.kgr.q25toolbox.modules.BesLoudnessController
import com.kgr.q25toolbox.modules.BtIdleController
import com.kgr.q25toolbox.modules.Dt2wController
import com.kgr.q25toolbox.modules.ExtraDimController
import com.kgr.q25toolbox.modules.LocationIdleController
import com.kgr.q25toolbox.modules.TelemetryController
import com.kgr.q25toolbox.modules.TickerController
import com.kgr.q25toolbox.service.Q25AccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports/imports Q25 Toolbox's SharedPreferences-backed modules ("q25tweaks",
 * "ticker_notifications") plus root-persisted script/schedule state (BtIdle,
 * LocationIdle, Extra Dim's night schedule, BesLoudness' schedule, Dt2w,
 * Global Telemetry Block), to/from a single JSON document via Storage Access
 * Framework Uris. Ported from Key2Toolbox.
 *
 * Selective per [BackupModule]: "q25tweaks" is one file shared by many modules,
 * so its individual KEYS are filtered by [Q25TWEAKS_MODULE_MAP].
 *
 * JSON shape:
 * {
 *   "app_version": "v3.0",
 *   "exported_at": "2026-08-29T12:00:00Z",
 *   "prefs": {
 *     "q25tweaks": { "someKey": {"type": "boolean", "value": true}, ... },
 *     "ticker_notifications": { "enabled": {"type": "boolean", "value": true}, ... }
 *   },
 *   "bt_idle": { "timeout_min": 15 },
 *   "location_idle": { "timeout_min": 15 },
 *   "extra_dim_schedule": { "start_minutes": 1320, "end_minutes": 420 },
 *   "besloudness_schedule": { "start_minutes": 1320, "end_minutes": 420 },
 *   "dt2w": { "enabled": true },
 *   "telemetry": { "enabled": true }
 * }
 *
 * Modules with a live-only effect (Extra Dim's / BesLoudness' on-off + level,
 * applied immediately as Settings writes) are out of scope - only what persists
 * across reboot is backed up. KeyRemap / ProximitySensor / RecentsTweaks are
 * not covered yet.
 */
object SettingsBackup {

    /** Modules the backup/restore UI lets the user select individually. */
    enum class BackupModule(@androidx.annotation.StringRes val labelRes: Int) {
        PIN_KEYBOARD(R.string.title_pin_keyboard),
        IME_BLOCK(R.string.title_ime_block),
        IME_SUGGESTIONS(R.string.title_ime_suggestions),
        CHAT_COMPOSER(R.string.title_chat_composer),
        CALCULATOR(R.string.title_calculator_input),
        IN_CALL_SHORTCUTS(R.string.title_in_call_shortcuts),
        CALL_SCREEN_RECOVERY(R.string.title_call_screen_recovery),
        APP_SCALING(R.string.title_app_scaling),
        AUTO_FOCUS(R.string.title_auto_focus),
        BATTERY_USAGE(R.string.title_battery_usage),
        BT_IDLE(R.string.title_bt_idle),
        LOCATION_IDLE(R.string.title_location_idle),
        EXTRA_DIM(R.string.title_extra_dim),
        BES_LOUDNESS(R.string.title_besloudness),
        DT2W(R.string.title_dt2w),
        TELEMETRY(R.string.title_telemetry),
        TICKER_NOTIFICATIONS(R.string.title_ticker_notifications),
    }

    /** Which BackupModule owns each key in the shared "q25tweaks" prefs file. */
    private val Q25TWEAKS_MODULE_MAP: Map<String, BackupModule> = mapOf(
        Q25AccessibilityService.KEY_PIN_INPUT to BackupModule.PIN_KEYBOARD,
        Q25AccessibilityService.KEY_IME_BLOCK to BackupModule.IME_BLOCK,
        Q25AccessibilityService.KEY_IME_BLOCK_APPS to BackupModule.IME_BLOCK,
        Q25AccessibilityService.KEY_IME_SAVED to BackupModule.IME_BLOCK,
        Q25AccessibilityService.KEY_IME_SUGGESTIONS to BackupModule.IME_SUGGESTIONS,
        Q25AccessibilityService.KEY_CHAT_COMPOSER to BackupModule.CHAT_COMPOSER,
        Q25AccessibilityService.KEY_CALCULATOR to BackupModule.CALCULATOR,
        Q25AccessibilityService.KEY_IN_CALL_SHORTCUTS to BackupModule.IN_CALL_SHORTCUTS,
        Q25AccessibilityService.KEY_CALL_SCREEN_RECOVERY to BackupModule.CALL_SCREEN_RECOVERY,
        Q25AccessibilityService.KEY_SCALING_APPS to BackupModule.APP_SCALING,
        AutoFocusController.KEY_AUTO_FOCUS to BackupModule.AUTO_FOCUS,
        AutoFocusController.KEY_AUTO_FOCUS_APPS to BackupModule.AUTO_FOCUS,
        BatteryUsageController.KEY_RESET_THRESHOLD to BackupModule.BATTERY_USAGE,
    )

    private const val Q25TWEAKS_PREFS = "q25tweaks"
    private const val TICKER_PREFS = "ticker_notifications"

    // Restoring this needs TickerController.setEnabled() rather than a bare pref
    // write, since that call also grants/revokes the notification-listener access.
    private const val TICKER_KEY_ENABLED = "enabled"

    private fun prefValueToJson(value: Any?): JSONObject? {
        val entry = JSONObject()
        when (value) {
            is Boolean -> entry.put("type", "boolean").put("value", value)
            is Int -> entry.put("type", "int").put("value", value)
            is Long -> entry.put("type", "long").put("value", value)
            is Float -> entry.put("type", "float").put("value", value.toDouble())
            is String -> entry.put("type", "string").put("value", value)
            is Set<*> -> entry.put("type", "stringset").put("value", JSONArray(value.toList()))
            else -> return null
        }
        return entry
    }

    fun exportToJson(
        context: Context,
        appVersion: String,
        modules: Set<BackupModule> = BackupModule.entries.toSet()
    ): JSONObject {
        val prefsJson = JSONObject()

        // q25tweaks: filter individual KEYS by which module owns them.
        val q25tweaks = context.getSharedPreferences(Q25TWEAKS_PREFS, Context.MODE_PRIVATE)
        val q25tweaksJson = JSONObject()
        for ((key, value) in q25tweaks.all) {
            val owner = Q25TWEAKS_MODULE_MAP[key] ?: continue
            if (owner !in modules) continue
            val entry = prefValueToJson(value) ?: continue
            q25tweaksJson.put(key, entry)
        }
        if (q25tweaksJson.length() > 0) prefsJson.put(Q25TWEAKS_PREFS, q25tweaksJson)

        // ticker_notifications: whole file is one module.
        if (BackupModule.TICKER_NOTIFICATIONS in modules) {
            val tickerPrefs = context.getSharedPreferences(TICKER_PREFS, Context.MODE_PRIVATE)
            val tickerJson = JSONObject()
            for ((key, value) in tickerPrefs.all) {
                val entry = prefValueToJson(value) ?: continue
                tickerJson.put(key, entry)
            }
            prefsJson.put(TICKER_PREFS, tickerJson)
        }

        val root = JSONObject()
        root.put("app_version", appVersion)
        root.put(
            "exported_at",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        )
        root.put("prefs", prefsJson)

        if (BackupModule.BT_IDLE in modules && BtIdleController.isPersisted()) {
            BtIdleController.persistedTimeout()?.let {
                root.put("bt_idle", JSONObject().put("timeout_min", it))
            }
        }
        if (BackupModule.LOCATION_IDLE in modules && LocationIdleController.isPersisted()) {
            LocationIdleController.persistedTimeout()?.let {
                root.put("location_idle", JSONObject().put("timeout_min", it))
            }
        }
        if (BackupModule.EXTRA_DIM in modules && ExtraDimController.isScheduleEnabled()) {
            root.put(
                "extra_dim_schedule",
                JSONObject()
                    .put("start_minutes", ExtraDimController.persistedStartMinutes())
                    .put("end_minutes", ExtraDimController.persistedEndMinutes())
            )
        }
        if (BackupModule.BES_LOUDNESS in modules && BesLoudnessController.isScheduleEnabled()) {
            root.put(
                "besloudness_schedule",
                JSONObject()
                    .put("start_minutes", BesLoudnessController.persistedStartMinutes())
                    .put("end_minutes", BesLoudnessController.persistedEndMinutes())
            )
        }
        if (BackupModule.DT2W in modules && Dt2wController.isPersisted()) {
            root.put("dt2w", JSONObject().put("enabled", true))
        }
        if (BackupModule.TELEMETRY in modules && TelemetryController.isPersisted()) {
            root.put("telemetry", JSONObject().put("enabled", true))
        }

        return root
    }

    /**
     * Applies a previously exported JSON document, restricted to [modules].
     * Unknown pref files / keys are skipped; existing keys not in the backup are
     * left untouched (merge, not wipe-then-restore).
     */
    fun importFromJson(
        context: Context,
        root: JSONObject,
        modules: Set<BackupModule> = BackupModule.entries.toSet()
    ): ImportResult {
        val prefsJson = root.optJSONObject("prefs")
            ?: return ImportResult.Failure("JSON has no \"prefs\" section — not a Q25 Toolbox backup file?")

        var restoredKeys = 0
        val scriptModulesRestored = mutableSetOf<BackupModule>()

        val q25tweaksJson = prefsJson.optJSONObject(Q25TWEAKS_PREFS)
        if (q25tweaksJson != null) {
            val editor = context.getSharedPreferences(Q25TWEAKS_PREFS, Context.MODE_PRIVATE).edit()
            val keys = q25tweaksJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val owner = Q25TWEAKS_MODULE_MAP[key] ?: continue
                if (owner !in modules) continue
                if (applyPrefEntry(editor, key, q25tweaksJson.getJSONObject(key))) restoredKeys++
            }
            editor.apply()
        }

        if (BackupModule.TICKER_NOTIFICATIONS in modules) {
            val tickerJson = prefsJson.optJSONObject(TICKER_PREFS)
            if (tickerJson != null) {
                val editor = context.getSharedPreferences(TICKER_PREFS, Context.MODE_PRIVATE).edit()
                val keys = tickerJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == TICKER_KEY_ENABLED) continue
                    if (applyPrefEntry(editor, key, tickerJson.getJSONObject(key))) restoredKeys++
                }
                editor.apply()

                tickerJson.optJSONObject(TICKER_KEY_ENABLED)?.let { e ->
                    if (e.optString("type") == "boolean") {
                        try {
                            TickerController.setEnabled(context, e.getBoolean("value"))
                            restoredKeys++
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        if (BackupModule.BT_IDLE in modules) {
            root.optJSONObject("bt_idle")?.let { j ->
                val timeout = j.optInt("timeout_min", -1)
                if (timeout > 0) {
                    try {
                        BtIdleController.setEnabled(context, true, timeout)
                        scriptModulesRestored += BackupModule.BT_IDLE
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (BackupModule.LOCATION_IDLE in modules) {
            root.optJSONObject("location_idle")?.let { j ->
                val timeout = j.optInt("timeout_min", -1)
                if (timeout > 0) {
                    try {
                        LocationIdleController.setEnabled(context, true, timeout)
                        scriptModulesRestored += BackupModule.LOCATION_IDLE
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (BackupModule.EXTRA_DIM in modules) {
            root.optJSONObject("extra_dim_schedule")?.let { j ->
                val s = j.optInt("start_minutes", -1)
                val e = j.optInt("end_minutes", -1)
                if (s in 0..1439 && e in 0..1439) {
                    try {
                        ExtraDimController.setScheduleEnabled(context, true, s, e)
                        scriptModulesRestored += BackupModule.EXTRA_DIM
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (BackupModule.BES_LOUDNESS in modules) {
            root.optJSONObject("besloudness_schedule")?.let { j ->
                val s = j.optInt("start_minutes", -1)
                val e = j.optInt("end_minutes", -1)
                if (s in 0..1439 && e in 0..1439) {
                    try {
                        BesLoudnessController.setScheduleEnabled(context, true, s, e)
                        scriptModulesRestored += BackupModule.BES_LOUDNESS
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (BackupModule.DT2W in modules) {
            root.optJSONObject("dt2w")?.let { j ->
                if (j.optBoolean("enabled", false)) {
                    try {
                        Dt2wController.setEnabled(context, true)
                        scriptModulesRestored += BackupModule.DT2W
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (BackupModule.TELEMETRY in modules) {
            root.optJSONObject("telemetry")?.let { j ->
                if (j.optBoolean("enabled", false)) {
                    try {
                        TelemetryController.setEnabled(context, true)
                        scriptModulesRestored += BackupModule.TELEMETRY
                    } catch (_: Exception) {
                    }
                }
            }
        }

        return ImportResult.Success(restoredKeys, scriptModulesRestored)
    }

    private fun applyPrefEntry(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        entry: JSONObject
    ): Boolean {
        when (entry.optString("type")) {
            "boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
            "int" -> editor.putInt(key, entry.getInt("value"))
            "long" -> editor.putLong(key, entry.getLong("value"))
            "float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
            "string" -> editor.putString(key, entry.getString("value"))
            "stringset" -> {
                val arr = entry.getJSONArray("value")
                editor.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
            }
            else -> return false
        }
        return true
    }

    fun writeToUri(context: Context, uri: Uri, json: JSONObject) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) }
            ?: error("Could not open output stream for $uri")
    }

    fun readFromUri(context: Context, uri: Uri): JSONObject {
        val text = context.contentResolver.openInputStream(uri)?.use {
            BufferedReader(InputStreamReader(it)).readText()
        } ?: error("Could not open input stream for $uri")
        return JSONObject(text)
    }

    sealed class ImportResult {
        data class Success(
            val restoredKeys: Int,
            val scriptModulesRestored: Set<BackupModule> = emptySet()
        ) : ImportResult()

        data class Failure(val message: String) : ImportResult()
    }
}
