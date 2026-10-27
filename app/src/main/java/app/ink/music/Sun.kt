package app.ink.music

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Sunlight: the light sensor, listened to only while ink music is on screen, steps the dim words up a brightness
 * ([Ink.sun]) in bright light (past 10,000 lux, daylight outside) and back down indoors (under 3,000: a gap so it
 * doesn't flicker at the edge).
 */
class Sun(private val c: Context) : DefaultLifecycleObserver, SensorEventListener {
    private val sensors = c.getSystemService(SensorManager::class.java)
    private val light = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)

    override fun onResume(owner: LifecycleOwner) {
        light?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause(owner: LifecycleOwner) {
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val lux = e.values.firstOrNull() ?: return
        if (!Ink.sun && lux > 10_000f) Ink.sun = true
        else if (Ink.sun && lux < 3_000f) Ink.sun = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
