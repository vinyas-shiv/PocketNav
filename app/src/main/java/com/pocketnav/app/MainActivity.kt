package com.pocketnav.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

object TelemetryProtocol {
    const val SERVICE_NAME = "PocketNavRFCOMM"
    val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

    const val PACKET_TYPE_GPS: Byte = 0x01
    const val PACKET_TYPE_ROUTE_DATA: Byte = 0x02
    const val GPS_PAYLOAD_SIZE_BYTES = 24

    const val PREFS_NAME = "pocketnav_prefs"
    const val PREF_KEY_DEVICE_ADDRESS = "target_bt_address"
    const val EXTRA_DEVICE_ADDRESS = "extra_target_bt_address"

    data class TelemetryFrame(
        val latitude: Double,
        val longitude: Double,
        val bearing: Float,
        val speed: Float
    )

    fun encodeGpsPayload(frame: TelemetryFrame): ByteArray {
        val buffer = ByteBuffer.allocate(GPS_PAYLOAD_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.putDouble(frame.latitude)
        buffer.putDouble(frame.longitude)
        buffer.putFloat(frame.bearing)
        buffer.putFloat(frame.speed)
        return buffer.array()
    }

    fun decodeGpsPayload(bytes: ByteArray): TelemetryFrame {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return TelemetryFrame(
            latitude = buffer.double,
            longitude = buffer.double,
            bearing = buffer.float,
            speed = buffer.float
        )
    }

    fun writeGpsPacket(out: DataOutputStream, frame: TelemetryFrame) {
        val payload = encodeGpsPayload(frame)
        synchronized(out) {
            out.writeByte(PACKET_TYPE_GPS.toInt())
            out.write(payload)
            out.flush()
        }
    }

    fun writeRouteDataPacket(out: DataOutputStream, geoJsonString: String) {
        val utf8Bytes = geoJsonString.toByteArray(StandardCharsets.UTF_8)
        synchronized(out) {
            out.writeByte(PACKET_TYPE_ROUTE_DATA.toInt())
            out.writeInt(utf8Bytes.size)
            out.write(utf8Bytes)
            out.flush()
        }
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerPairedDevices: Spinner
    private lateinit var btnStartCommander: Button
    private lateinit var btnStartDashboard: Button
    private lateinit var tvCommanderStatus: TextView
    private lateinit var tvTelemetryReadout: TextView

    private var pairedDevicesList: List<BluetoothDevice> = emptyList()

    companion object {
        private const val REQ_PERMISSIONS_COMMANDER = 101
        private const val REQ_PERMISSIONS_DASHBOARD = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerPairedDevices = findViewById(R.id.spinnerPairedDevices)
        btnStartCommander = findViewById(R.id.btnStartCommander)
        btnStartDashboard = findViewById(R.id.btnStartDashboard)
        tvCommanderStatus = findViewById(R.id.tvCommanderStatus)
        tvTelemetryReadout = findViewById(R.id.tvTelemetryReadout)

        populatePairedDevicesIfPermitted()

        btnStartCommander.setOnClickListener {
            if (hasCommanderPermissions()) {
                populatePairedDevicesIfPermitted()
                launchCommanderActivity()
            } else {
                requestCommanderPermissions()
            }
        }

        btnStartDashboard.setOnClickListener {
            if (hasDashboardPermissions()) {
                launchDashboardActivity()
            } else {
                requestDashboardPermissions()
            }
        }
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasCommanderPermissions(): Boolean {
        val fineLoc = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val btConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        return fineLoc && btConnect
    }

    private fun requestCommanderPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_PERMISSIONS_COMMANDER)
    }

    private fun hasDashboardPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    private fun requestDashboardPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ),
                REQ_PERMISSIONS_DASHBOARD
            )
        } else {
            launchDashboardActivity()
        }
    }

    @SuppressLint("MissingPermission")
    private fun populatePairedDevicesIfPermitted() {
        if (!hasDashboardPermissions()) return
        val bonded = getBluetoothAdapter()?.bondedDevices?.toList() ?: emptyList()
        pairedDevicesList = bonded
        val labels = if (bonded.isEmpty()) {
            listOf("No paired Bluetooth devices found (Pair Phone 2 in Settings)")
        } else {
            bonded.map { "${it.name ?: "Unknown"} (${it.address})" }
        }
        spinnerPairedDevices.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
    }

    private fun launchCommanderActivity() {
        val idx = spinnerPairedDevices.selectedItemPosition
        val selectedDevice = if (idx in pairedDevicesList.indices) {
            pairedDevicesList[idx]
        } else {
            pairedDevicesList.firstOrNull()
        }

        val address = selectedDevice?.address
        if (address != null) {
            getSharedPreferences(TelemetryProtocol.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(TelemetryProtocol.PREF_KEY_DEVICE_ADDRESS, address)
                .apply()
        }

        val intent = Intent(this, CommanderActivity::class.java).apply {
            if (address != null) {
                putExtra(TelemetryProtocol.EXTRA_DEVICE_ADDRESS, address)
            }
        }
        startActivity(intent)
    }

    private fun launchDashboardActivity() {
        startActivity(Intent(this, DashboardActivity::class.java))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS_COMMANDER && hasCommanderPermissions()) {
            populatePairedDevicesIfPermitted()
            launchCommanderActivity()
        } else if (requestCode == REQ_PERMISSIONS_DASHBOARD && hasDashboardPermissions()) {
            launchDashboardActivity()
        }
    }
}
