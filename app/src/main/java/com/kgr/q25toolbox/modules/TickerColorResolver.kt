package com.kgr.q25toolbox.modules

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette

/**
 * Resolves the ticker banner's background color per [TickerSettings.ColorMode].
 */
object TickerColorResolver {

    private const val APP_ICON_SATURATION_CAP = 0.45f
    private const val APP_ICON_MIN_LIGHTNESS = 0.16f
    private const val APP_ICON_MAX_LIGHTNESS = 0.30f

    fun resolveBackgroundColor(
        context: Context,
        packageName: String,
        notification: Notification? = null
    ): Int =
        when (TickerSettings.colorMode(context)) {
            TickerSettings.ColorMode.FIXED -> TickerSettings.fixedColor(context)
            TickerSettings.ColorMode.APP_ICON ->
                appIconColor(context, packageName, notification) ?: TickerSettings.fixedColor(context)
            TickerSettings.ColorMode.MONET ->
                monetColor(context) ?: TickerSettings.fixedColor(context)
        }

    /**
     * Resolves color from notification.color (if set by the app) or from the app's raw,
     * un-themed APK icon (bypassing system icon pack overlays that might color it gray).
     */
    private fun appIconColor(context: Context, packageName: String, notification: Notification?): Int? {
        // 1. Check if the notification itself provided an official brand accent color.
        if (notification != null && notification.color != 0 && notification.color != Notification.COLOR_DEFAULT) {
            return muted(notification.color)
        }

        // 2. Extract dominant color from the app's raw APK icon (bypassing launcher icon packs).
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val resources = pm.getResourcesForApplication(appInfo)
            val iconDrawable = if (appInfo.icon != 0) {
                try {
                    resources.getDrawable(appInfo.icon, null)
                } catch (_: Exception) {
                    pm.getApplicationIcon(packageName)
                }
            } else {
                pm.getApplicationIcon(packageName)
            }
            val bitmap = iconDrawable.toBitmap(width = 48, height = 48)
            val palette = Palette.from(bitmap).generate()
            val dominant = palette.dominantSwatch?.rgb
                ?: palette.vibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: return null
            muted(dominant)
        } catch (_: Exception) {
            null
        }
    }

    private fun muted(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = hsl[1].coerceAtMost(APP_ICON_SATURATION_CAP)
        hsl[2] = hsl[2].coerceIn(APP_ICON_MIN_LIGHTNESS, APP_ICON_MAX_LIGHTNESS)
        return ColorUtils.HSLToColor(hsl)
    }

    /** Android 12+ Monet dynamic (wallpaper-based) accent color; null on older API levels. */
    private fun monetColor(context: Context): Int? {
        if (Build.VERSION.SDK_INT < 31) return null
        return try {
            val resId = context.resources.getIdentifier("system_accent1_600", "color", "android")
            if (resId != 0) context.getColor(resId) else null
        } catch (_: Exception) {
            null
        }
    }
}
