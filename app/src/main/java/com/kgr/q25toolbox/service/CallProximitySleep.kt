package com.kgr.q25toolbox.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.kgr.q25toolbox.core.RootShell
import java.util.concurrent.ExecutorService

/**
 * Turns the screen off when the phone is held to the ear during a call, and back on when it is moved away.
 *
 * Replaces the system's own handling on ROMs where it does not work: the dialer takes the proximity wake lock
 * and the sensor flips correctly, yet the screen stays lit against the cheek (a pocket-dial and accidental
 * emergency-call risk). Idea and behaviour follow Key2 Toolbox's "Call Proximity Sleep", but event-driven
 * instead of a root loop polling `dumpsys` every few seconds:
 *
 *  - the call is detected from the audio mode ([AudioManager.OnModeChangedListener], no permission needed);
 *  - the proximity sensor is registered only while a call is on, and only "near" held for [NEAR_DEBOUNCE_MS]
 *    counts (a hand passing over the sensor does not blank the screen);
 *  - it only acts on the handset earpiece: speakerphone, Bluetooth and wired audio leave the screen alone;
 *  - sleep and wake are injected as KEYCODE_SLEEP / KEYCODE_WAKEUP through the root shell (off the main
 *    thread). WAKEUP only ever wakes, so it can never flip a screen the user has just lit back off.
 *  - it wakes the screen when the sensor reports "far", when the call ends, and when the service stops, so the
 *    screen is never left dark. This holds even when the SYSTEM blanked the screen itself: on the Q25 the
 *    framework's own proximity handling turns the screen off fine but is slow, or fails, to bring it back, so
 *    "far after a near we saw" wakes the screen whoever turned it off (with a retry, since the first wake can be
 *    swallowed while the system is still settling). If the screen is already off when our debounce expires, we
 *    do not sleep it a second time.
 */
@RequiresApi(Build.VERSION_CODES.S)
class CallProximitySleep(private val context: Context, private val io: ExecutorService) {

    private companion object {
        const val TAG = "Q25Toolbox"
        const val NEAR_DEBOUNCE_MS = 250L
        const val KEYCODE_SLEEP = 223
        const val KEYCODE_WAKEUP = 224
        const val WAKE_DELAY_MS = 250L   // let the system do its own wake first, then check
        const val WAKE_RETRY_MS = 900L   // a second try if the screen is still dark
        const val POST_CALL_MS = 30_000L // keep listening this long after hang-up (see endOfCall)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val proximity: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val display: Display? = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    /**
     * The panel is actually off. PowerManager.isInteractive is useless here: when the system blanks the screen for
     * proximity the device stays "awake" (and a plain WAKEUP on an awake device does nothing), so the display
     * state is the only honest signal.
     */
    private fun dark() = display?.state == Display.STATE_OFF

    private var started = false
    private var inCall = false
    private var sensing = false
    private var near = false
    private var forcedAsleep = false
    // A "near" was seen during this call and no "far" has answered it yet: the screen may be dark and owes a wake.
    private var sawNear = false
    // The call is over but we keep listening for a "far" that owes the screen a wake (see endOfCall).
    private var postCall = false

    private val modeListener = AudioManager.OnModeChangedListener { mode -> handler.post { onMode(mode) } }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val max = proximity?.maximumRange ?: return
            val nowNear = (event.values.firstOrNull() ?: return) < max
            if (nowNear == near) return
            near = nowNear
            Log.d(TAG, "CallProx: sensor ${if (near) "NEAR" else "far"} (inCall=$inCall)")
            handler.removeCallbacks(sleepCheck)
            handler.removeCallbacks(wakeCheck)
            if (near) {
                sawNear = true
                handler.postDelayed(sleepCheck, NEAR_DEBOUNCE_MS)
            } else if (sawNear) {
                handler.postDelayed(wakeCheck, WAKE_DELAY_MS)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val sleepCheck = Runnable {
        if (!near || !inCall || forcedAsleep) return@Runnable
        if (!onEarpiece()) { Log.d(TAG, "CallProx: near but not on the earpiece, leaving the screen on"); return@Runnable }
        if (dark()) { Log.d(TAG, "CallProx: already dark (system did it), not sleeping again"); return@Runnable }
        forcedAsleep = true
        Log.d(TAG, "CallProx: sleep")
        io.execute { RootShell.run("input keyevent $KEYCODE_SLEEP") }
    }

    /**
     * Sensor says "far": if the screen is still dark, wake it (twice at most, a first WAKEUP can be lost while the
     * system's own proximity handling is still settling). Only after a near we saw, so a screen the user turned
     * off by hand is not lit by the sensor flicking.
     */
    private val wakeCheck = object : Runnable {
        private var attempts = 0
        override fun run() {
            if (!(inCall || postCall) || near) { attempts = 0; return }
            if (!dark()) {
                Log.d(TAG, "CallProx: far, screen is on")
                sawNear = false; forcedAsleep = false; attempts = 0
                if (postCall) finishPostCall()
                return
            }
            attempts++
            Log.d(TAG, "CallProx: far but screen is dark, waking (try $attempts)")
            // Try 1: a plain WAKEUP. If the system's proximity state is what keeps the panel off, the device is
            // still "awake" and that does nothing; try 2 then forces a real sleep and wake, which makes the display
            // controller look at the (now far) sensor again.
            val cmd = if (attempts == 1) "input keyevent $KEYCODE_WAKEUP"
            else "input keyevent $KEYCODE_SLEEP; sleep 0.3; input keyevent $KEYCODE_WAKEUP"
            io.execute { RootShell.run(cmd) }
            if (attempts < 2) handler.postDelayed(this, WAKE_RETRY_MS) else {
                attempts = 0; sawNear = false; forcedAsleep = false
                if (postCall) finishPostCall()
            }
        }
    }

    /** Handset audio only: no speakerphone, Bluetooth SCO or wired headset. */
    @Suppress("DEPRECATION")
    private fun onEarpiece(): Boolean =
        audio != null && !audio.isSpeakerphoneOn && !audio.isBluetoothScoOn && !audio.isWiredHeadsetOn

    fun start() {
        if (started || audio == null || proximity == null) {
            if (proximity == null) Log.d(TAG, "CallProx: no proximity sensor, not starting")
            return
        }
        started = true
        audio.addOnModeChangedListener(context.mainExecutor, modeListener)
        onMode(audio.mode)
        Log.d(TAG, "CallProx: started (mode=${audio.mode})")
    }

    fun stop() {
        if (!started) return
        started = false
        audio?.removeOnModeChangedListener(modeListener)
        handler.removeCallbacks(sleepCheck)
        handler.removeCallbacks(wakeCheck)
        handler.removeCallbacks(postCallEnd)
        postCall = false
        setSensing(false)
        inCall = false
        wakeIfOwed("stopped")
    }

    private fun onMode(mode: Int) {
        val nowInCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        if (nowInCall == inCall) return
        inCall = nowInCall
        Log.d(TAG, "CallProx: audio mode $mode -> inCall=$inCall")
        if (nowInCall) {
            handler.removeCallbacks(postCallEnd)
            postCall = false
            setSensing(true)
        } else {
            handler.removeCallbacks(sleepCheck)
            handler.removeCallbacks(wakeCheck)
            endOfCall()
        }
    }

    /**
     * Hang-up. The dialer releases its proximity lock WITHOUT turning the screen on ("turning on the screen
     * immediately false", seen in its log), on purpose: if the other side hangs up while the phone is at the ear, the
     * screen should stay dark until it moves away. Two things used to go wrong here: the mode change reaches us
     * ~3 s after the call really ended, and we stopped listening to the sensor right then, so when the system's own
     * wake failed nobody was left to notice the "far". Now, if a near left the screen dark, we keep the sensor on for
     * [POST_CALL_MS]: already far means wake now, still near means wait for the first "far" (the usual wake, with its
     * retry). Without a pending near there is nothing to do and we stop listening at once.
     */
    private fun endOfCall() {
        if (!(sawNear || forcedAsleep)) { setSensing(false); return }
        postCall = true
        handler.removeCallbacks(postCallEnd)
        handler.postDelayed(postCallEnd, POST_CALL_MS)
        Log.d(TAG, "CallProx: call ended, near=$near dark=${dark()}, listening for far up to ${POST_CALL_MS / 1000}s")
        if (!near) handler.postDelayed(wakeCheck, WAKE_DELAY_MS)
    }

    private val postCallEnd = Runnable { Log.d(TAG, "CallProx: post-call window over"); finishPostCall() }

    private fun finishPostCall() {
        handler.removeCallbacks(postCallEnd)
        postCall = false
        sawNear = false
        forcedAsleep = false
        setSensing(false)
    }

    private fun setSensing(on: Boolean) {
        if (on == sensing) return
        sensing = on
        if (on) {
            near = false
            sawNear = false
            sensors?.registerListener(sensorListener, proximity, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            sensors?.unregisterListener(sensorListener)
            near = false
        }
    }

    /** Wakes the screen if we put it to sleep, or if it is dark after a near we saw (the system may have done it). */
    private fun wakeIfOwed(reason: String) {
        val owed = forcedAsleep || (sawNear && dark())
        forcedAsleep = false
        sawNear = false
        if (!owed) return
        Log.d(TAG, "CallProx: wake ($reason)")
        io.execute { RootShell.run("input keyevent $KEYCODE_WAKEUP") }
    }
}
