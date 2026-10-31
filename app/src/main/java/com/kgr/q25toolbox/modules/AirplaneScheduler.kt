package com.kgr.q25toolbox.modules

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.receiver.AirplaneSchedulerReceiver
import com.kgr.q25toolbox.service.Q25AccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Scheduled airplane mode, driven by root instead of Shizuku.
 *
 * Design: the alarm only says "something may have changed". [apply] is state-based and idempotent:
 * it looks at the clock, decides whether any schedule window is open, and moves the radios only as
 * far as needed. That makes a missed alarm, a reboot or a clock change self-healing (every one of
 * them just triggers [refresh]), and it is what lets a window end revert ONLY what we changed: if
 * airplane mode was already on, or the user switched it off mid-window, we leave it alone.
 */
object AirplaneScheduler {

    /** [days] bit 0 = Monday ... bit 6 = Sunday; 0 = one-shot ([onceStartMs] holds its start). */
    data class Schedule(
        val id: Long,
        val name: String,
        val startMin: Int = 23 * 60,
        val endMin: Int = 7 * 60,
        val days: Int = 0b1111111,
        val wifiOff: Boolean = true,
        val enabled: Boolean = true,
        val skipUntilMs: Long = 0L,
        val onceStartMs: Long = 0L,
    ) {
        val durationMin: Int get() = ((endMin - startMin + 1440) % 1440).let { if (it == 0) 1440 else it }
    }

    const val KEY_SCHEDULES = "airplane_schedules"
    private const val KEY_OWNED_AIRPLANE = "airplane_owned_airplane"
    private const val KEY_OWNED_WIFI = "airplane_owned_wifi"
    const val ACTION_TICK = "com.kgr.q25toolbox.AIRPLANE_TICK"

    private val lock = Any()

    private fun prefs(c: Context) = c.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ storage

    fun load(c: Context): List<Schedule> = try {
        val arr = JSONArray(prefs(c).getString(KEY_SCHEDULES, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Schedule(
                o.getLong("id"), o.optString("name"), o.optInt("start", 23 * 60), o.optInt("end", 7 * 60),
                o.optInt("days", 0b1111111), o.optBoolean("wifiOff", true), o.optBoolean("enabled", true),
                o.optLong("skipUntil", 0L), o.optLong("onceStart", 0L),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(c: Context, list: List<Schedule>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("start", it.startMin).put("end", it.endMin)
                .put("days", it.days).put("wifiOff", it.wifiOff).put("enabled", it.enabled)
                .put("skipUntil", it.skipUntilMs).put("onceStart", it.onceStartMs))
        }
        prefs(c).edit().putString(KEY_SCHEDULES, arr.toString()).apply()
    }

    /** Applies [change] to the schedule with [id] (null removes it), persists, then re-evaluates. Blocking (root). */
    fun update(c: Context, id: Long, change: (Schedule) -> Schedule?) {
        synchronized(lock) {
            val list = load(c).mapNotNull { if (it.id == id) change(it) else it }
            save(c, list)
        }
        refresh(c)
    }

    fun add(c: Context, name: String) {
        synchronized(lock) {
            val s = Schedule(System.currentTimeMillis(), name)
            save(c, load(c) + s)
        }
        refresh(c)
    }

    /** Next start of a one-shot at [startMin] (today if still ahead, else tomorrow). */
    fun nextOnce(startMin: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = dayStart(now, 0)
        cal.add(Calendar.MINUTE, startMin)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    // ------------------------------------------------------------------ windows

    private fun dayStart(now: Long, offsetDays: Int): Calendar = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, offsetDays)
    }

    /** Open windows (start, end) in ms around [now], skipped ones already removed. */
    fun windows(s: Schedule, now: Long): List<Pair<Long, Long>> {
        val dur = s.durationMin * 60_000L
        val raw = if (s.days == 0) {
            if (s.onceStartMs > 0) listOf(s.onceStartMs) else emptyList()
        } else {
            (-1..8).mapNotNull { d ->
                val day = dayStart(now, d)
                // Calendar: SUNDAY=1..SATURDAY=7  ->  bit 0 = Monday ... bit 6 = Sunday
                val bit = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7
                if (s.days and (1 shl bit) == 0) null else day.timeInMillis + s.startMin * 60_000L
            }
        }
        return raw.filter { it >= s.skipUntilMs }.map { it to it + dur }
    }

    /** Start of the occurrence to skip: the open window if there is one, else the next one. Returns its end. */
    fun skipTarget(s: Schedule, now: Long): Long? =
        windows(s, now).filter { it.second > now }.minByOrNull { it.first }?.second

    private fun active(list: List<Schedule>, now: Long) =
        list.filter { it.enabled && windows(it, now).any { w -> now >= w.first && now < w.second } }

    /** Next moment any open/close boundary happens, or null with nothing to wait for. */
    fun nextTransition(c: Context, now: Long = System.currentTimeMillis()): Long? =
        load(c).filter { it.enabled }.flatMap { s -> windows(s, now).flatMap { listOf(it.first, it.second) } }
            .filter { it > now }.minOrNull()

    // ------------------------------------------------------------------ radios (root)

    fun isAirplaneOn(): Boolean =
        RootShell.run("settings get global airplane_mode_on").outString.trim() == "1"

    private fun isWifiOn(): Boolean =
        RootShell.run("settings get global wifi_on").outString.trim().let { it.isNotEmpty() && it != "0" && it != "null" }

    private fun setAirplane(on: Boolean) {
        val v = if (on) 1 else 0
        // `cmd connectivity airplane-mode` (Android 11+) does the full toggle; the settings+broadcast pair is the pre-11 path.
        RootShell.run(
            "cmd connectivity airplane-mode ${if (on) "enable" else "disable"} || " +
                "{ settings put global airplane_mode_on $v; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on; }"
        )
    }

    // ------------------------------------------------------------------ engine

    /** Brings the radios in line with the clock, then arms the next alarm. Blocking (root): never on the main thread. */
    fun refresh(c: Context) {
        synchronized(lock) {
            apply(c)
            arm(c)
        }
    }

    private fun apply(c: Context) {
        val now = System.currentTimeMillis()
        val p = prefs(c)
        var list = load(c)

        // A one-shot is spent once its window has closed.
        val spent = list.filter { it.days == 0 && it.enabled && it.onceStartMs > 0 && now >= it.onceStartMs + it.durationMin * 60_000L }
        if (spent.isNotEmpty()) {
            list = list.map { if (it in spent) it.copy(enabled = false) else it }
            save(c, list)
        }

        val open = active(list, now)
        var ownedAir = p.getBoolean(KEY_OWNED_AIRPLANE, false)
        var ownedWifi = p.getBoolean(KEY_OWNED_WIFI, false)

        if (open.isNotEmpty()) {
            // Wi-Fi state must be read BEFORE airplane mode, which switches it off by itself.
            if (open.any { it.wifiOff } && !ownedWifi && isWifiOn()) ownedWifi = true
            if (!ownedAir && !isAirplaneOn()) {
                setAirplane(true)
                ownedAir = true
            }
            if (ownedWifi) RootShell.run("svc wifi disable")
        } else {
            // Revert only what we changed, and only if it is still as we left it.
            if (ownedAir && isAirplaneOn()) setAirplane(false)
            if (ownedWifi && !isWifiOn()) RootShell.run("svc wifi enable")
            ownedAir = false
            ownedWifi = false
        }
        p.edit().putBoolean(KEY_OWNED_AIRPLANE, ownedAir).putBoolean(KEY_OWNED_WIFI, ownedWifi).apply()
    }

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 0,
        Intent(c, AirplaneSchedulerReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun arm(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = nextTransition(c)
        if (next == null) {
            am.cancel(pending(c))
            return
        }
        // SCHEDULE_EXACT_ALARM is user-revocable on Android 12-12L; with root we can grant it ourselves.
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            RootShell.run("appops set ${c.packageName} SCHEDULE_EXACT_ALARM allow")
        }
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(c)) // may slip by minutes in Doze
    }
}
