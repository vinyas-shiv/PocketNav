package com.pocketnav.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object TelemetryProtocol {
    const val SERVICE_NAME = "PocketNavRFCOMM"
    val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    const val PACKET_SIZE_BYTES = 24

    data class TelemetryFrame(
        val latitude: Double,
        val longitude: Double,
        val bearing: Float,
        val speed: Float
    )

    fun encode(frame: TelemetryFrame): ByteArray {
        val buffer = ByteBuffer.allocate(PACKET_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.putDouble(frame.latitude)
        buffer.putDouble(frame.longitude)
        buffer.putFloat(frame.bearing)
        buffer.putFloat(frame.speed)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): TelemetryFrame {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return TelemetryFrame(buffer.double, buffer.double, buffer.float, buffer.float)
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerPairedDevices: Spinner
    private lateinit var btnStartCommander: Button
    private lateinit var btnStartDashboard: Button
    private lateinit var tvCommanderStatus: TextView
    private lateinit var tvTelemetryReadout: TextView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private val isCommanderRunning = AtomicBoolean(false)
    private var rfcommSocket: BluetoothSocket? = null
    private var rfcommOutputStream: OutputStream? = null
    private var pairedDevicesList: List<BluetoothDevice> = emptyList()
    private var packetsSentCount: Long = 0L

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

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        populatePairedDevicesIfPermitted()

        btnStartCommander.setOnClickListener {
            if (isCommanderRunning.get()) {
                stopCommanderRole()
            } else if (hasCommanderPermissions()) {
                populatePairedDevicesIfPermitted()
                startCommanderRole()
            } else {
                requestCommanderPermissions()
            }
        }

        btnStartDashboard.setOnClickListener {
            if (isCommanderRunning.get()) stopCommanderRole()
            if (hasDashboardPermissions()) launchDashboardActivity() else requestDashboardPermissions()
        }
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasCommanderPermissions(): Boolean {
        val fineLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val btConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
        return fineLoc && btConnect
    }

    private fun requestCommanderPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_PERMISSIONS_COMMANDER)
    }

    private fun hasDashboardPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

    private fun requestDashboardPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
                REQ_PERMISSIONS_DASHBOARD
            )
        } else launchDashboardActivity()
    }

    @SuppressLint("MissingPermission")
    private fun populatePairedDevicesIfPermitted() {
        if (!hasDashboardPermissions()) return
        val bonded = getBluetoothAdapter()?.bondedDevices?.toList() ?: emptyList()
        pairedDevicesList = bonded
        val labels = if (bonded.isEmpty()) listOf("No paired Bluetooth devices found")
        else bonded.map { "${it.name ?: "Unknown"} (${it.address})" }
        spinnerPairedDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    @SuppressLint("MissingPermission")
    private fun startCommanderRole() {
        val btAdapter = getBluetoothAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth first.", Toast.LENGTH_LONG).show()
            return
        }
        if (pairedDevicesList.isEmpty()) populatePairedDevicesIfPermitted()
        val idx = spinnerPairedDevices.selectedItemPosition
        val targetDevice = if (idx in pairedDevicesList.indices) pairedDevicesList[idx] else pairedDevicesList.firstOrNull()
        if (targetDevice == null) {
            Toast.makeText(this, "Pair Phone 2 in Bluetooth settings first.", Toast.LENGTH_LONG).show()
            return
        }

        isCommanderRunning.set(true)
        packetsSentCount = 0L
        btnStartCommander.text = "Stop Commander (Phone 1)"
        tvCommanderStatus.text = "Status: Connecting RFCOMM to ${targetDevice.name ?: targetDevice.address}..."

        Thread {
            while (isCommanderRunning.get()) {
                if (rfcommOutputStream == null) {
                    try {
                        btAdapter.cancelDiscovery()
                        val socket = targetDevice.createRfcommSocketToServiceRecord(TelemetryProtocol.SERVICE_UUID)
                        socket.connect()
                        rfcommSocket = socket
                        rfcommOutputStream = socket.outputStream
                        runOnUiThread {
                            tvCommanderStatus.text = "Status: Connected to ${targetDevice.name ?: targetDevice.address} • Streaming GPS"
                        }
                    } catch (e: IOException) {
                        closeCommanderSocket()
                        try { Thread.sleep(2000L) } catch (_: InterruptedException) { break }
                    }
                } else {
                    try { Thread.sleep(500L) } catch (_: InterruptedException) { break }
                }
            }
        }.start()

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
            .setMinUpdateIntervalMillis(250L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val frame = TelemetryProtocol.TelemetryFrame(loc.latitude, loc.longitude, loc.bearing, loc.speed)
                sendTelemetryPayload(TelemetryProtocol.encode(frame), frame)
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
    }

    private fun sendTelemetryPayload(payload: ByteArray, frame: TelemetryProtocol.TelemetryFrame) {
        Thread {
            val out = rfcommOutputStream
            var delivered = false
            if (out != null) {
                try {
                    synchronized(this) {
                        out.write(payload)
                        out.flush()
                    }
                    packetsSentCount++
                    delivered = true
                } catch (_: IOException) {
                    closeCommanderSocket()
                }
            }
            val hexPreview = payload.joinToString(" ") { String.format("%02X", it) }
            runOnUiThread {
                tvTelemetryReadout.text = String.format(
                    Locale.US,
                    "TX Frame #%d (%s)\nLAT: %.6f\nLON: %.6f\nBRG: %.1f deg\nSPD: %.2f m/s\nRAW: %s",
                    packetsSentCount,
                    if (delivered) "SENT" else "WAITING BT",
                    frame.latitude, frame.longitude, frame.bearing, frame.speed, hexPreview
                )
            }
        }.start()
    }

    private fun stopCommanderRole() {
        isCommanderRunning.set(false)
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        closeCommanderSocket()
        btnStartCommander.text = "Start as Commander (Phone 1)"
        tvCommanderStatus.text = "Status: Commander stopped."
    }

    private fun closeCommanderSocket() {
        try { rfcommOutputStream?.close() } catch (_: IOException) {}
        try { rfcommSocket?.close() } catch (_: IOException) {}
        rfcommOutputStream = null
        rfcommSocket = null
    }

    private fun launchDashboardActivity() {
        startActivity(Intent(this, DashboardActivity::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS_COMMANDER && hasCommanderPermissions()) {
            populatePairedDevicesIfPermitted()
            startCommanderRole()
        } else if (requestCode == REQ_PERMISSIONS_DASHBOARD && hasDashboardPermissions()) {
            launchDashboardActivity()
        }
    }

    override fun onDestroy() {
        stopCommanderRole()
        super.onDestroy()
    }
}

class DashboardActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var tvDashboardStatus: TextView
    private lateinit var tvDashboardCoords: TextView
    private lateinit var tvDashboardMetrics: TextView

    private var mapLibreMap: MapLibreMap? = null
    private val isListening = AtomicBoolean(false)
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private val breadcrumbPoints = mutableListOf<Pair<Double, Double>>()
    private var hasInitializedGrid = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_dashboard)

        mapView = findViewById(R.id.mapView)
        tvDashboardStatus = findViewById(R.id.tvDashboardStatus)
        tvDashboardCoords = findViewById(R.id.tvDashboardCoords)
        tvDashboardMetrics = findViewById(R.id.tvDashboardMetrics)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.setStyle(Style.Builder().fromJson(buildOfflineStyleJson())) { style ->
                updateOfflineReferenceGrid(style, 12.9716, 77.5946)
                val initialCam = CameraPosition.Builder()
                    .target(LatLng(12.9716, 77.5946))
                    .zoom(17.0)
                    .tilt(45.0)
                    .bearing(0.0)
                    .build()
                map.moveCamera(CameraUpdateFactory.newCameraPosition(initialCam))
            }
        }
        startBluetoothRfcommServer()
    }

    private fun buildOfflineStyleJson(): String = """
        {
          "version": 8,
          "name": "PocketNav Offline 3D Telemetry Map",
          "sources": {
            "grid-source": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
            "trail-source": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
            "puck-source": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } }
          },
          "layers": [
            { "id": "background", "type": "background", "paint": { "background-color": "#0B1221" } },
            { "id": "grid-layer", "type": "line", "source": "grid-source", "paint": { "line-color": "#1E293B", "line-width": 1.5 } },
            { "id": "trail-layer", "type": "line", "source": "trail-source", "paint": { "line-color": "#38BDF8", "line-width": 5.0 } },
            { "id": "puck-core-layer", "type": "circle", "source": "puck-source", "paint": { "circle-radius": 8.0, "circle-color": "#34D399", "circle-stroke-width": 3.0, "circle-stroke-color": "#FFFFFF" } }
          ]
        }
    """.trimIndent()

    private fun updateOfflineReferenceGrid(style: Style, centerLat: Double, centerLon: Double) {
        val gridSource = style.getSourceAs<GeoJsonSource>("grid-source") ?: return
        val step = 0.0005
        val span = 15
        val features = StringBuilder()
        for (i in -span..span) {
            val lat = centerLat + i * step
            if (features.isNotEmpty()) features.append(",")
            features.append(String.format(Locale.US, "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[%.6f,%.6f],[%.6f,%.6f]]}}", centerLon - span * step, lat, centerLon + span * step, lat))
        }
        for (j in -span..span) {
            val lon = centerLon + j * step
            features.append(",")
            features.append(String.format(Locale.US, "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[%.6f,%.6f],[%.6f,%.6f]]}}", lon, centerLat - span * step, lon, centerLat + span * step))
        }
        gridSource.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[$features]}")
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothRfcommServer() {
        val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            tvDashboardStatus.text = "BLUETOOTH OFF • ENABLE BLUETOOTH TO LISTEN"
            return
        }
        isListening.set(true)
        Thread {
            val packetBuffer = ByteArray(TelemetryProtocol.PACKET_SIZE_BYTES)
            while (isListening.get()) {
                try {
                    val srv = btAdapter.listenUsingRfcommWithServiceRecord(TelemetryProtocol.SERVICE_NAME, TelemetryProtocol.SERVICE_UUID)
                    serverSocket = srv
                    val socket = srv.accept()
                    clientSocket = socket
                    try { srv.close() } catch (_: IOException) {}
                    runOnUiThread { tvDashboardStatus.text = "CONNECTED • LIVE 3D TRACKING" }
                    val dataIn = DataInputStream(socket.inputStream)
                    while (isListening.get() && socket.isConnected) {
                        dataIn.readFully(packetBuffer, 0, TelemetryProtocol.PACKET_SIZE_BYTES)
                        val frame = TelemetryProtocol.decode(packetBuffer)
                        runOnUiThread { onTelemetryFrameReceived(frame) }
                    }
                } catch (_: IOException) {
                    closeDashboardSockets()
                    try { Thread.sleep(1000L) } catch (_: InterruptedException) { break }
                }
            }
        }.start()
    }

    private fun onTelemetryFrameReceived(frame: TelemetryProtocol.TelemetryFrame) {
        if (frame.latitude.isNaN() || frame.longitude.isNaN()) return
        tvDashboardCoords.text = String.format(Locale.US, "LAT: %.6f   LON: %.6f", frame.latitude, frame.longitude)
        tvDashboardMetrics.text = String.format(Locale.US, "SPD: %.1f m/s (%.1f km/h) | BRG: %.1f° | TILT: 45.0° 3D", frame.speed, frame.speed * 3.6f, frame.bearing)

        val map = mapLibreMap ?: return
        map.getStyle { style ->
            if (!hasInitializedGrid) {
                hasInitializedGrid = true
                updateOfflineReferenceGrid(style, frame.latitude, frame.longitude)
            }
            val puckJson = String.format(Locale.US, "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[%.6f,%.6f]}}]}", frame.longitude, frame.latitude)
            style.getSourceAs<GeoJsonSource>("puck-source")?.setGeoJson(puckJson)

            breadcrumbPoints.add(Pair(frame.longitude, frame.latitude))
            if (breadcrumbPoints.size > 200) breadcrumbPoints.removeAt(0)
            if (breadcrumbPoints.size >= 2) {
                val coords = breadcrumbPoints.joinToString(",") { String.format(Locale.US, "[%.6f,%.6f]", it.first, it.second) }
                style.getSourceAs<GeoJsonSource>("trail-source")?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$coords]}}]}")
            }
        }

        val cameraPosition = CameraPosition.Builder()
            .target(LatLng(frame.latitude, frame.longitude))
            .zoom(17.5)
            .tilt(45.0)
            .bearing(frame.bearing.toDouble())
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 450)
    }

    private fun closeDashboardSockets() {
        try { clientSocket?.close() } catch (_: IOException) {}
        try { serverSocket?.close() } catch (_: IOException) {}
        clientSocket = null
        serverSocket = null
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); mapView.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onDestroy() {
        isListening.set(false)
        closeDashboardSockets()
        mapView.onDestroy()
        super.onDestroy()
    }
}
