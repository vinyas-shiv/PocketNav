package com.pocketnav.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

object TelemetryProtocol {
    const val SERVICE_NAME = "PocketNavTelemetry"
    val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

    const val PACKET_TYPE_LOCATION: Byte = 0x01
    const val PACKET_TYPE_ROUTE_DATA: Byte = 0x02
    const val PACKET_TYPE_MAP_ROADS: Byte = 0x03
    const val GPS_PAYLOAD_SIZE_BYTES = 24

    sealed class IncomingPacket {
        data class LocationFrame(
            val latitude: Double,
            val longitude: Double,
            val bearing: Float,
            val speed: Float
        ) : IncomingPacket()

        data class RouteDataFrame(
            val geoJsonString: String
        ) : IncomingPacket()

        data class MapRoadsFrame(
            val roadsEnvelopeJson: String
        ) : IncomingPacket()
    }

    @Synchronized
    fun writeLocationPacket(
        out: DataOutputStream,
        latitude: Double,
        longitude: Double,
        bearing: Float,
        speed: Float
    ) {
        val buffer = ByteBuffer.allocate(GPS_PAYLOAD_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.putDouble(latitude)
        buffer.putDouble(longitude)
        buffer.putFloat(bearing)
        buffer.putFloat(speed)
        out.writeByte(PACKET_TYPE_LOCATION.toInt())
        out.write(buffer.array())
        out.flush()
    }

    @Synchronized
    fun writeRouteDataPacket(out: DataOutputStream, geoJsonOrEnvelope: String) {
        val utf8Bytes = geoJsonOrEnvelope.toByteArray(StandardCharsets.UTF_8)
        out.writeByte(PACKET_TYPE_ROUTE_DATA.toInt())
        out.writeInt(utf8Bytes.size)
        out.write(utf8Bytes)
        out.flush()
    }

    @Synchronized
    fun writeMapRoadsPacket(out: DataOutputStream, roadsEnvelopeJson: String) {
        val utf8Bytes = roadsEnvelopeJson.toByteArray(StandardCharsets.UTF_8)
        out.writeByte(PACKET_TYPE_MAP_ROADS.toInt())
        out.writeInt(utf8Bytes.size)
        out.write(utf8Bytes)
        out.flush()
    }

    @Throws(IOException::class)
    fun readNextPacket(input: DataInputStream): IncomingPacket {
        while (true) {
            when (val packetType = input.readByte()) {
                PACKET_TYPE_LOCATION -> {
                    val payload = ByteArray(GPS_PAYLOAD_SIZE_BYTES)
                    input.readFully(payload)
                    val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                    return IncomingPacket.LocationFrame(
                        latitude = buffer.double,
                        longitude = buffer.double,
                        bearing = buffer.float,
                        speed = buffer.float
                    )
                }
                PACKET_TYPE_ROUTE_DATA -> {
                    val length = input.readInt()
                    if (length <= 0 || length > 10 * 1024 * 1024) {
                        throw IOException("Invalid ROUTE_DATA payload length: $length")
                    }
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    val jsonStr = String(payload, StandardCharsets.UTF_8)
                    return IncomingPacket.RouteDataFrame(jsonStr)
                }
                PACKET_TYPE_MAP_ROADS -> {
                    val length = input.readInt()
                    if (length <= 0 || length > 15 * 1024 * 1024) {
                        throw IOException("Invalid MAP_ROADS payload length: $length")
                    }
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    val jsonStr = String(payload, StandardCharsets.UTF_8)
                    return IncomingPacket.MapRoadsFrame(jsonStr)
                }
                else -> {
                    throw IOException("Unknown packet type header: $packetType")
                }
            }
        }
    }
}

class MainActivity : AppCompatActivity() {

    private var pendingRole: Role? = null

    private enum class Role {
        COMMANDER,
        DASHBOARD
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        proceedWithRole(pendingRole)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStartCommander = findViewById<Button>(R.id.btnStartCommander)
        val btnStartDashboard = findViewById<Button>(R.id.btnStartDashboard)

        btnStartCommander.setOnClickListener {
            pendingRole = Role.COMMANDER
            checkAndRequestPermissions(Role.COMMANDER)
        }

        btnStartDashboard.setOnClickListener {
            pendingRole = Role.DASHBOARD
            checkAndRequestPermissions(Role.DASHBOARD)
        }
    }

    private fun checkAndRequestPermissions(role: Role) {
        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (role == Role.COMMANDER) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            proceedWithRole(role)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun proceedWithRole(role: Role?) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            when (role) {
                Role.COMMANDER -> startActivity(Intent(this, CommanderActivity::class.java))
                Role.DASHBOARD -> startActivity(Intent(this, DashboardActivity::class.java))
                null -> Unit
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Launch error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
