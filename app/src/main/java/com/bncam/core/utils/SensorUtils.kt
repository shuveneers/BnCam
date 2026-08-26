package com.bncam.core.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.atan2

class SensorUtils(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    var roll = mutableFloatStateOf(0f)
    var pitch = mutableFloatStateOf(0f)

    // NIEUW: Voor de UI rotatie
    var uiRotationDegrees = mutableFloatStateOf(0f)

    // NIEUW: Voor de JPEG rotatie (Surface.ROTATION_0, etc.)
    var captureRotation = mutableIntStateOf(Surface.ROTATION_0)

    private val orientationEventListener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return

            // 1. Check of de gebruiker auto-rotate aan heeft staan
            val isAutoRotateEnabled = Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0
            ) == 1

            // 2. Fysieke hoek van het toestel uitlezen
            val deviceAngle = when (orientation) {
                in 45..134 -> 90   // Telefoon naar rechts gekanteld
                in 135..224 -> 180 // Ondersteboven
                in 225..314 -> 270 // Telefoon naar links gekanteld
                else -> 0          // Rechtop
            }

            // 3. UI ROTATIE CORRIGEREN (Tegen de richting in)
            if (isAutoRotateEnabled) {
                uiRotationDegrees.floatValue = when (deviceAngle) {
                    90 -> -90f  // Fysiek rechtsom (+90) -> UI moet linksom (-90) draaien
                    270 -> 90f  // Fysiek linksom (-90 of 270) -> UI moet rechtsom (+90) draaien
                    180 -> 180f
                    else -> 0f
                }
            } else {
                uiRotationDegrees.floatValue = 0f
            }

            // 4. CAPTURE ROTATIE OMDRAAIEN (Voorkomt foto op z'n kop)
            captureRotation.intValue = when (deviceAngle) {
                90 -> Surface.ROTATION_90    // Rechtsom
                270 -> Surface.ROTATION_270  // Linksom
                180 -> Surface.ROTATION_180
                else -> Surface.ROTATION_0
            }
        }
    }

    fun register() {
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        orientationEventListener.enable()
    }

    fun unregister() {
        sensorManager.unregisterListener(this)
        orientationEventListener.disable()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GRAVITY || event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // atan2 meet de hoek van de zwaartekracht op het X en Y vlak van je scherm.
            val calculatedRoll = -Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
            val calculatedPitch = Math.toDegrees(atan2(z.toDouble(), y.toDouble())).toFloat()

            // UX UPGRADE: Een simpele "Low-Pass Filter"
            // Dit haalt de zenuwachtige trilling uit je waterpas en maakt de lijn boterzacht.
            // 1.0 is direct (trillerig), 0.15 is enorm vloeiend.
            val smoothingFactor = 0.4f

            if (roll.floatValue == 0f) {
                // Eerste meting? Geef direct door zonder vertraging
                roll.floatValue = calculatedRoll
                pitch.floatValue = calculatedPitch
            } else {
                // Daarna vloeiend in elkaar over laten vloeien
                roll.floatValue = (roll.floatValue * (1f - smoothingFactor)) + (calculatedRoll * smoothingFactor)
                pitch.floatValue = (pitch.floatValue * (1f - smoothingFactor)) + (calculatedPitch * smoothingFactor)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}