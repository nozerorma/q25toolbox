package com.kgr.q25toolbox.core

import android.content.Context
import android.os.Build

/** ROM families this app knows how to adapt to. */
enum class Rom { BENOS, ZINWAOS, LINEAGE, UNKNOWN }

/**
 * Result of ROM detection.
 *
 * [lineageMajor] is the LineageOS major version (22, 23, ...) when [rom] is LINEAGE, else null.
 * [evidence] lists the properties that decided the outcome, so the user (and a bug report) can
 * see why a given profile was chosen.
 */
data class RomInfo(
    val rom: Rom,
    val lineageMajor: Int?,
    val auto: Rom,
    val overridden: Boolean,
    val evidence: String,
)

/**
 * Detects the running ROM from build properties; a manual override (set from the first-run
 * dialog or Settings) wins over detection.
 *
 * Detection is property-based on purpose: the code never hardcodes device paths per ROM here,
 * it only decides which *profile* applies. Modules then branch on [Rom] / [isLineage].
 *
 * Confidence per branch:
 *  - LINEAGE: `ro.lineage.version` (or a `lineage_*` build flavor) is set by every LineageOS
 *    build, including unofficial ports. Reliable.
 *  - BENOS: `ro.fota.version` contains "BenOS" (verified on a real BenOS build, see
 *    q25-device-facts). Reliable.
 *  - ZINWAOS: *heuristic*. Any other build exposing `ro.fota.*` (OEM OTA client) or a Zinwa
 *    manufacturer string. Not verified on stock ZinwaOS; if it misfires the user can override.
 *  - anything else: UNKNOWN, which triggers the manual chooser.
 */
object RomProfile {
    private const val PREFS = "rom_profile"
    private const val KEY_OVERRIDE = "override"

    @Volatile private var cached: RomInfo? = null

    private fun prop(key: String): String = try {
        // Non-root getprop is enough for ro.* build properties.
        Runtime.getRuntime().exec(arrayOf("getprop", key))
            .inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) {
        ""
    }

    /** Pure classification, separated from I/O so it can be unit-tested. */
    internal fun classify(props: Map<String, String>, manufacturer: String, sdk: Int): Triple<Rom, Int?, String> {
        fun p(k: String) = props[k].orEmpty()
        val lineage = p("ro.lineage.version")
        val flavor = p("ro.build.flavor")
        val fota = p("ro.fota.version")
        return when {
            lineage.isNotEmpty() || flavor.startsWith("lineage_") -> {
                // ro.lineage.build.version is "22.2" / "23.0"; fall back to SDK (35 -> 22, 36 -> 23).
                val major = p("ro.lineage.build.version").substringBefore('.').toIntOrNull()
                    ?: when (sdk) { 35 -> 22; 36 -> 23; else -> null }
                Triple(Rom.LINEAGE, major, "ro.lineage.version=$lineage flavor=$flavor")
            }
            fota.contains("benos", ignoreCase = true) ->
                Triple(Rom.BENOS, null, "ro.fota.version=$fota")
            fota.isNotEmpty() || p("ro.fota.oem").isNotEmpty() || manufacturer.contains("zinwa", true) ->
                Triple(Rom.ZINWAOS, null, "ro.fota.version=$fota ro.fota.oem=${p("ro.fota.oem")} (heuristic)")
            else -> Triple(Rom.UNKNOWN, null, "no known ROM marker")
        }
    }

    private fun detectAuto(): Triple<Rom, Int?, String> {
        val keys = listOf("ro.lineage.version", "ro.lineage.build.version", "ro.build.flavor", "ro.fota.version", "ro.fota.oem")
        val manufacturer = try { Build.MANUFACTURER ?: "" } catch (_: Throwable) { "" }
        val sdk = try { Build.VERSION.SDK_INT } catch (_: Throwable) { 0 }
        return classify(keys.associateWith(::prop), manufacturer, sdk)
    }

    /** Blocking (spawns `getprop`); call off the main thread on first use. Result is cached. */
    fun get(context: Context): RomInfo {
        cached?.let { return it }
        val (auto, major, evidence) = detectAuto()
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OVERRIDE, null)
        val override = saved?.let { runCatching { Rom.valueOf(it) }.getOrNull() }
        val rom = override ?: auto
        // Lineage version is only meaningful if Lineage really is what's running.
        val info = RomInfo(rom, if (auto == Rom.LINEAGE && rom == Rom.LINEAGE) major else null, auto, override != null, evidence)
        cached = info
        return info
    }

    /** Persists a manual choice; pass null to go back to auto-detection. */
    fun setOverride(context: Context, rom: Rom?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (rom == null) remove(KEY_OVERRIDE) else putString(KEY_OVERRIDE, rom.name)
        }.apply()
        cached = null
    }

    /**
     * True when unbinding the `Q25_keyboard` i2c driver can panic the kernel. Hardware safety,
     * so it uses auto-detection only and ignores the manual override.
     *
     * Observed on LineageOS 23 / kernel 5.10 (2026-10-02): `bbqX0kbd.ko` registers a display
     * notifier at probe (`mtk_disp_notifier_register`) but imports no unregister symbol, so after
     * an unbind the callback keeps running on freed state. The next panel-enable event oopses
     * (NULL deref at +0x2aa in `bbqX0kbd_disp_notifier_callback`) and the phone reboots.
     * BenOS has used unbind/bind for months without this, so it keeps that path unchanged.
     */
    fun keyboardRebindUnsafe(): Boolean = rebindUnsafe

    private val rebindUnsafe: Boolean by lazy { detectAuto().first == Rom.LINEAGE }

    /** True when auto-detection says this is LineageOS (ignores the manual override): for hardware-level behaviour. */
    fun autoDetectedLineage(): Boolean = rebindUnsafe

    /** True when the user still has to pick a ROM: detection failed and no override exists. */
    fun needsChoice(context: Context): Boolean = get(context).rom == Rom.UNKNOWN

    fun isLineage(context: Context) = get(context).rom == Rom.LINEAGE
}
