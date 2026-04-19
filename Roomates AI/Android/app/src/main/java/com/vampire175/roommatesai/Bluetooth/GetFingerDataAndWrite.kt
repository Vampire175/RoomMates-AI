package com.vampire175.roommatesai.Bluetooth

import android.util.Log
import com.vampire175.roommatesai.GestureControls.fragment.CameraFragment

class GetFingerDataAndWrite(private val cameraFragment: CameraFragment) {

    var started: Boolean = false

    private var lastGestureName: String? = null
    private var lastSentTime: Long = 0L
    private val thresholdMs: Long = 500L // minimum ms between writes

    fun doTaskAccordingToGesture(gestureName: String?, isUserVerified: Boolean) {
        if (gestureName == null) return

        started = true

        val now = System.currentTimeMillis()
        val isSameGesture = gestureName == lastGestureName
        val isWithinThreshold = (now - lastSentTime) < thresholdMs

        // Skip if gesture hasn't changed OR if called too soon
        if (isSameGesture || isWithinThreshold) {
            Log.d("Gesture", "Skipped '$gestureName' — same: $isSameGesture, throttled: $isWithinThreshold")
            return
        }

        val fingerStates = when (gestureName) {
            "index"     -> listOf(0, 1, 1, 1, 1)
            "middle"    -> listOf(0, 0, 1, 1, 1)
            "ring"      -> listOf(0, 0, 0, 1, 1)
            "pinky"     -> listOf(0, 0, 0, 0, 1)
            "openpalm"  -> listOf(0, 0, 0, 0, 0)
            "closepalm" -> listOf(1, 1, 1, 1, 1)
            else        -> listOf(1, 1, 1, 1, 1)
        }

        if (BluetoothManager.isConnected() && isUserVerified) {
            BluetoothManager.sendStates(fingerStates)
            lastGestureName = gestureName
            lastSentTime = now
            Log.d("Gesture", "Sent gesture '$gestureName' via Bluetooth")
        } else {
            Log.e("Gesture", "Bluetooth not connected")
        }
    }
}