package com.vampire175.roommatesai.GestureControls

import android.util.Log
import com.vampire175.roommatesai.Bluetooth.BluetoothManager
import com.vampire175.roommatesai.GestureControls.fragment.CameraFragment
import kotlinx.coroutines.*

class GetFingerDataAndWrite(private val cameraFragement: CameraFragment) {


    private val gestureShowDelay: Long = 3000  // 3 seconds
    private var lastGestureTime: Long = 0L
    private var lastGesture: String = ""
    var started: Boolean=false

    fun doTaskAccordingToGesture(gestureName: String?) {


        var fingerStates = listOf(0, 0, 0, 0, 0)
        if (gestureName == null) return

        val currentTime = System.currentTimeMillis()

        // Only react if enough time passed AND gesture changed
        if (gestureName != lastGesture && currentTime - lastGestureTime > gestureShowDelay) {



            if (gestureName == "start"&&!started) {
                cameraFragement.activity?.runOnUiThread {
                    cameraFragement.ChangeHGRStartedText(true)
                }

                started=true
                fingerStates = listOf(1, 1, 1, 1, 1)

                if (BluetoothManager.isConnected()) {
                    BluetoothManager.sendStates(fingerStates)
                    Log.d("Gesture", "Sent START gesture via Bluetooth")


                } else {
                    Log.e("Gesture", "Bluetooth not connected")
                }
            }

            lastGesture = gestureName
            lastGestureTime = currentTime
        }

        if(started){
            if(gestureName=="index"){
                fingerStates = listOf(0,1,1,1,1)

            }
            else if(gestureName=="middle"){
                fingerStates = listOf(0,0,1,1,1)

            }
            else if(gestureName=="ring"){
                fingerStates = listOf(0,0,0,1,1)

            }
            else if(gestureName=="pinky"){
                fingerStates = listOf(0,0,0,0,1)

            }
            else if(gestureName=="openpalm"){
                fingerStates = listOf(0,0,0,0,0)

            }
            else if(gestureName=="closepalm") {
                fingerStates = listOf(1, 1, 1, 1, 1)
            }
            if (BluetoothManager.isConnected()) {
                BluetoothManager.sendStates(fingerStates)
                Log.d("Gesture", "Sent START gesture via Bluetooth")

            } else {
                Log.e("Gesture", "Bluetooth not connected")
            }

            CoroutineScope(Dispatchers.Main).launch {

                delay(5000)
                started=false
                cameraFragement.ChangeHGRStartedText(false)
            }


        }
        else{
            //donothing
        }
    }
}
