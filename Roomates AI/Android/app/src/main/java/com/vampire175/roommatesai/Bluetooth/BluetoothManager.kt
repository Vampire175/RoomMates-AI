package com.vampire175.roommatesai.Bluetooth


import android.bluetooth.BluetoothSocket
import java.io.OutputStream

object BluetoothManager {

    var btSocket: BluetoothSocket? = null
    var btOut: OutputStream? = null

    fun isConnected(): Boolean {
        return btOut != null
    }

    fun sendStates(states: List<Int>) {
        val out = btOut ?: return

        val data = states.map { it.toByte() }.toByteArray()

        try {
            out.write(data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun close() {
        try {
            btOut?.close()
            btSocket?.close()
        } catch (_: Exception) {
        }
        btOut = null
        btSocket = null
    }
}