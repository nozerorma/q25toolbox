package com.kgr.q25toolbox.modules

import android.content.Context
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.service.Q25AccessibilityService

/**
 * Per-app display scaling via global resolution switching.
 *
 * This ROM (BenOS/MTK) ignores every per-app scaling mechanism - the compat
 * DOWNSCALE_* changes and GameManager downscale are both no-ops, and `wm
 * density` has no effect either (verified on-device). The ONE display knob that
 * works is `wm size` (physical resolution). So "per-app scaling" is done by
 * switching the global resolution when a chosen app is foreground and resetting
 * on exit - the accessibility service already tracks the foreground app.
 *
 * Targets are full width×height resolutions (not just squares), so apps can be
 * given a taller portrait aspect. Presets follow duc1607's q25-res-changer.
 * Per-app targets are stored as a `pkg=WxH` StringSet in the `q25tweaks` prefs;
 * native entries aren't stored.
 */
object AppScalingController {

    const val NATIVE_W = 720
    const val NATIVE_H = 720

    /** A target resolution. Native (physical) means "off". */
    data class Res(val w: Int, val h: Int) {
        val isNative: Boolean get() = w == NATIVE_W && h == NATIVE_H
        fun encode(): String = "${w}x${h}"

        companion object {
            fun decode(s: String): Res? {
                val p = s.lowercase().split("x")
                if (p.size != 2) return null
                val w = p[0].trim().toIntOrNull() ?: return null
                val h = p[1].trim().toIntOrNull() ?: return null
                if (w <= 0 || h <= 0) return null
                return Res(w, h)
            }
        }
    }

    val NATIVE = Res(NATIVE_W, NATIVE_H)

    /**
     * Presets from q25-res-changer (the 780×780 "breaks SystemUI" entry is
     * deliberately omitted). The user can also enter a custom W×H.
     */
    val PRESETS: List<Res> = listOf(
        Res(720, 720),   // native / off
        Res(720, 772),
        Res(720, 960),
        Res(720, 1280),
        Res(720, 1440),
    )

    fun label(res: Res): String = when {
        res.isNative -> "Native (off)"
        res == Res(720, 772) -> "720×772 (a bit taller)"
        res == Res(720, 960) -> "720×960 (3:4 portrait)"
        res == Res(720, 1280) -> "720×1280 (9:16 portrait)"
        res == Res(720, 1440) -> "720×1440 (tall)"
        else -> "${res.w}×${res.h}"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)

    /** Map of package -> target resolution. Only non-native entries are stored. */
    fun entries(context: Context): Map<String, Res> {
        val raw = prefs(context)
            .getStringSet(Q25AccessibilityService.KEY_SCALING_APPS, emptySet()) ?: emptySet()
        return raw.mapNotNull { s ->
            val i = s.lastIndexOf('=')
            if (i <= 0) return@mapNotNull null
            val res = Res.decode(s.substring(i + 1)) ?: return@mapNotNull null
            s.substring(0, i) to res
        }.toMap()
    }

    fun resFor(context: Context, pkg: String): Res = entries(context)[pkg] ?: NATIVE

    fun setRes(context: Context, pkg: String, res: Res) {
        val map = entries(context).toMutableMap()
        if (res.isNative) map.remove(pkg) else map[pkg] = res
        val set = map.map { "${it.key}=${it.value.encode()}" }.toSet()
        prefs(context).edit()
            .putStringSet(Q25AccessibilityService.KEY_SCALING_APPS, set)
            .apply()
    }

    /** Applies a global resolution, or resets to physical for native.
     *
     * At 1000×1000 the smallest width exceeds 600dp (208 dpi × 1000px / 160 ≈ 769dp),
     * which triggers Android's large-screen/tablet mode and shows the taskbar.
     * We stash it explicitly to suppress that bar.
     */
    fun applyResolution(res: Res) {
        if (res.isNative) {
            RootShell.run("timeout 3 wm size reset ; settings delete secure taskbar_is_stashed")
        } else {
            RootShell.run("timeout 3 wm size ${res.w}x${res.h} ; settings put secure taskbar_is_stashed 1")
        }
    }
}
