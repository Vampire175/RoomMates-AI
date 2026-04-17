package com.vampire175.roommatesai.GestureControls

import android.util.Log
import com.vampire175.roommatesai.Bluetooth.BluetoothManager
import com.vampire175.roommatesai.GestureControls.fragment.CameraFragment

class GetFingerDataAndWrite(private val cameraFragment: CameraFragment) {

    private val gestureShowDelay: Long = 3000
    private var lastGestureTime: Long = 0L
    private var lastGesture: String = ""
    var started: Boolean = false

    fun doTaskAccordingToGesture(gestureName: String?) {
        if (gestureName == null) return

        val currentTime = System.currentTimeMillis()

        if (gestureName != lastGesture && currentTime - lastGestureTime > gestureShowDelay) {
            started = true

            val fingerStates = when (gestureName) {
                "index"     -> listOf(0, 1, 1, 1, 1)
                "middle"    -> listOf(0, 0, 1, 1, 1)
                "ring"      -> listOf(0, 0, 0, 1, 1)
                "pinky"     -> listOf(0, 0, 0, 0, 1)
                "openpalm"  -> listOf(0, 0, 0, 0, 0)
                "closepalm" -> listOf(1, 1, 1, 1, 1)
                else        -> listOf(1, 1, 1, 1, 1)
            }

            if (BluetoothManager.isConnected()) {
                BluetoothManager.sendStates(fingerStates)
                Log.d("Gesture", "Sent gesture '$gestureName' via Bluetooth")
            } else {
                Log.e("Gesture", "Bluetooth not connected")
            }


            lastGesture = gestureName
            lastGestureTime = currentTime
        }
    }
}