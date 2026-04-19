package com.vampire175.roommatesai

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.enableEdgeToEdge
import com.vampire175.roommatesai.Bluetooth.BluetoothManager
import com.vampire175.roommatesai.FaceRecognition.FaceRegistrationActivity   // ← NEW
import com.vampire175.roommatesai.GestureControls.MainActivity
import java.io.OutputStream
import java.util.UUID

class LaunchScreen : AppCompatActivity() {

    private var btSocket: BluetoothSocket? = null
    private var btOut: OutputStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_launch_screen)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.register_face)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // ── Existing: start gesture recognition ───────────────────────
        val hgstart: Button? = findViewById(R.id.hgstart)
        hgstart?.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // ── Existing: Bluetooth connect ───────────────────────────────
        val connectButton: Button? = findViewById(R.id.connect)
        connectButton?.setOnClickListener { showBluetoothDeviceDialog() }


        val registerFaceButton: Button? = findViewById(R.id.register_face)
        registerFaceButton?.setOnClickListener {
            startActivity(Intent(this, FaceRegistrationActivity::class.java))
        }

        requestBtPermissionsIfNeeded()
    }

    // ── Bluetooth (unchanged) ─────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun showBluetoothDeviceDialog() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth disabled", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth permission not granted", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val devices = adapter.bondedDevices?.toList() ?: emptyList()
        if (devices.isEmpty()) { Toast.makeText(this, "No paired devices", Toast.LENGTH_SHORT).show(); return }
        val names = devices.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Bluetooth device")
            .setItems(names) { _, which -> connectToDevice(devices[which]) }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Thread {
            try {
                val uuid   = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                val socket = device.createRfcommSocketToServiceRecord(uuid)
                socket.connect()
                btSocket = socket
                btOut    = socket.outputStream
                BluetoothManager.btSocket = socket
                BluetoothManager.btOut    = socket.outputStream
                runOnUiThread {
                    Toast.makeText(this, "Connected to ${device.name ?: "device"}", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "BT connect failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun requestBtPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN),
                1001
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        BluetoothManager.close()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
