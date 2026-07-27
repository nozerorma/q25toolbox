package com.kgr.q25toolbox.modules

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.kgr.q25toolbox.core.RootShell
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Controller for reading live proximity sensor events, continuous ambient light (Lux)
 * fine-tuning data from the hx32062 combo sensor, and triggering calibration activities.
 */
object ProximitySensorController {

    data class SensorData(
        val distanceCm: Float = -1f,
        val maxRangeCm: Float = -1f,
        val isNear: Boolean = false,
        val isAvailable: Boolean = false,
        val sensorName: String = "",
        val ambientLux: Float = -1f,
        val hasLux: Boolean = false
    )

    /**
     * Launch the OEM factory proximity sensor test activity via root shell.
     * P_SensorTestActivity is used to avoid the OEM firmware bug in PsensorProxTestActivity.
     * This is a raw sensor readout screen, not a calibration tool - the sensor's near/far
     * threshold is fixed in firmware and isn't exposed for adjustment.
     */
    fun launchCalibration(): Boolean {
        val result = RootShell.run("am start -n com.hodafone.factorytest/.sensor.P_SensorTestActivity")
        if (result.success) return true
        val fallback = RootShell.run("am start -n com.hodafone.factorytest/.sensor.PsensorTestActivity")
        return fallback.success
    }

    /**
     * Flow emitting live updates from both the proximity binary threshold sensor
     * AND the co-located light sensor (hx32062se_als Lux value) for fine-grained analog monitoring.
     */
    fun observeSensor(context: Context): Flow<SensorData> = callbackFlow {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val proxSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        if (sensorManager == null || proxSensor == null) {
            trySend(SensorData(isAvailable = false))
            close()
            return@callbackFlow
        }

        var currentData = SensorData(
            isAvailable = true,
            maxRangeCm = proxSensor.maximumRange,
            sensorName = proxSensor.name ?: "Proximity Sensor"
        )

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                when (event.sensor.type) {
                    Sensor.TYPE_PROXIMITY -> {
                        val distance = event.values.firstOrNull() ?: return
                        val isNear = distance < currentData.maxRangeCm
                        currentData = currentData.copy(
                            distanceCm = distance,
                            isNear = isNear
                        )
                        trySend(currentData)
                    }
                    Sensor.TYPE_LIGHT -> {
                        val lux = event.values.firstOrNull() ?: return
                        currentData = currentData.copy(
                            ambientLux = lux,
                            hasLux = true
                        )
                        trySend(currentData)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, proxSensor, SensorManager.SENSOR_DELAY_UI)
        if (lightSensor != null) {
            sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_UI)
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
}
