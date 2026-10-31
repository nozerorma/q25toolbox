package com.kgr.q25toolbox.modules

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.service.Q25AccessibilityService
import kotlin.math.abs

/**
 * Live battery power meter (the idea of Beam, MIT: github.com/montafra/beam, reimplemented here).
 * Power, current, voltage, temperature and charge state come from BatteryManager, so no root is
 * needed to read them. The ongoing notification is posted from whatever process is alive (the
 * accessibility service keeps ours up), so no foreground service is added.
 *
 * Measurement caveat: this is the figure at the battery gauge. It excludes nothing the phone draws
 * while on battery, but while charging it is what enters the cell, not what the charger delivers.
 */
object BatteryMeterController {

    const val KEY_ENABLED = "battery_meter_enabled"
    const val KEY_MA_UNITS = "battery_meter_ma_units"
    private const val KEY_CHARGING_SINCE = "battery_meter_charging_since"
    private const val CHANNEL = "battery_meter"
    private const val NOTIF_ID = 0x4b32
    private const val POLL_MS = 3_000L

    data class Reading(
        val percent: Int,
        val charging: Boolean,
        val full: Boolean,
        val tempC: Double,
        val volts: Double,
        /** Magnitude in amps; the sign is applied by [watts] / the UI from [charging]. */
        val amps: Double,
        val energyWh: Double?,
        val timeToFullMs: Long?,
        val chargingSinceMs: Long?,
    ) {
        val watts: Double get() = volts * amps
    }

    private fun prefs(c: Context) = c.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)

    /**
     * BATTERY_PROPERTY_CURRENT_NOW is specified in microamps, but some kernels report milliamps
     * (the wrong-power symptom Beam works around). This toggle is that workaround, off by default.
     */
    fun usesMilliamps(c: Context) = prefs(c).getBoolean(KEY_MA_UNITS, false)

    fun setMilliamps(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_MA_UNITS, on).apply()

    /** One synchronous sample. Cheap: a sticky broadcast plus three BatteryManager calls. */
    fun read(c: Context): Reading? {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        val charging = full || status == BatteryManager.BATTERY_STATUS_CHARGING
        val volts = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0
        val rawCurrent = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        // Unsupported properties return Long.MIN_VALUE; the sign convention differs per device, so only the
        // magnitude is used and the direction comes from the charge status instead.
        val amps = if (rawCurrent == Long.MIN_VALUE) 0.0
        else abs(rawCurrent) / (if (usesMilliamps(c)) 1e3 else 1e6)
        val counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) // microamp-hours
        val energy = if (counter > 0 && volts > 0) counter / 1e6 * volts else null
        val toFull = if (charging && !full) bm.computeChargeTimeRemaining().takeIf { it > 0 } else null

        // "Charging since" survives process death: stamped on the plug-in transition, cleared on unplug.
        val p = prefs(c)
        var since = p.getLong(KEY_CHARGING_SINCE, 0L)
        if (charging && since == 0L) { since = System.currentTimeMillis(); p.edit().putLong(KEY_CHARGING_SINCE, since).apply() }
        if (!charging && since != 0L) { since = 0L; p.edit().putLong(KEY_CHARGING_SINCE, 0L).apply() }

        return Reading(
            if (level < 0) 0 else level * 100 / scale, charging, full,
            i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0, volts, amps, energy, toFull,
            since.takeIf { it > 0 },
        )
    }

    // ---------------------------------------------------------------- notification

    private val handler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var screenOn = true

    private val poll = object : Runnable {
        override fun run() {
            post()
            if (screenOn && receiver != null) handler.postDelayed(this, POLL_MS)
        }
    }

    /** Idempotent. Main thread. Samples every few seconds while the screen is on; the system's own battery broadcasts cover screen-off. */
    fun start(context: Context) {
        val c = context.applicationContext
        if (receiver != null) return
        appCtx = c
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.battery_meter_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        // Android 13+: the user can revoke this silently; as root we can simply grant it.
        Thread { RootShell.run("pm grant ${c.packageName} android.permission.POST_NOTIFICATIONS") }.start()

        screenOn = (c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> { screenOn = true; handler.removeCallbacks(poll); handler.post(poll) }
                    Intent.ACTION_SCREEN_OFF -> { screenOn = false; handler.removeCallbacks(poll) }
                    else -> post()
                }
            }
        }
        receiver = r
        c.registerReceiver(r, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
        handler.post(poll)
    }

    fun stop(context: Context) {
        val c = context.applicationContext
        handler.removeCallbacks(poll)
        receiver?.let { runCatching { c.unregisterReceiver(it) } }
        receiver = null
        c.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    /** Starts or stops to match the saved toggle. */
    fun sync(context: Context) = if (isEnabled(context)) start(context) else stop(context)

    private fun post() {
        val c = appCtx ?: return
        val r = read(c) ?: return
        val sign = if (r.charging) "+" else "−"
        val title = if (r.watts > 0.005) "$sign${"%.2f".format(r.watts)} W" else "0 W"
        val text = "${r.percent}% · ${"%.2f".format(r.amps)} A · ${"%.2f".format(r.volts)} V · ${"%.1f".format(r.tempC)} °C"
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }
}
