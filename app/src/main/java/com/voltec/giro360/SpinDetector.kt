package com.voltec.giro360

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Detecta quando a plataforma 360 começa a girar usando o giroscópio do aparelho.
 * Quando a velocidade angular passa de [threshold] rad/s, chama [onSpinStart].
 *
 * Usado para iniciar a gravação automaticamente assim que a base gira.
 */
class SpinDetector(
    context: Context,
    private val threshold: Float = 1.2f,
    private val onSpinStart: () -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** Quando true, ignora a detecção (ex.: já está gravando). */
    @Volatile
    var paused: Boolean = false

    val isSupported: Boolean get() = gyroscope != null

    fun start() {
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (paused) return
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val speed = sqrt(x * x + y * y + z * z)
        if (speed > threshold) {
            paused = true // evita disparos repetidos; quem chama reativa quando quiser
            onSpinStart()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
