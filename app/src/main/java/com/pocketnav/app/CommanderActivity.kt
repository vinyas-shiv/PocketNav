package com.pocketnav.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

class CommanderActivity : AppCompatActivity() {

    private lateinit var spinnerPairedDevices: Spinner
    private lateinit var btnStartCommander: Button
    private lateinit var btnStartDashboard: Button
    private lateinit var tvCommanderStatus: TextView
    private lateinit var tvTelemetryReadout: TextView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private val isStreaming = AtomicBoolean(false)
    private var rfcommSocket: BluetoothSocket? = null
    private var rfcommDataOut: DataOutputStream? = null
    private var pairedDevicesList: List<BluetoothDevice> = emptyList()

    @Volatile
    private var latestLocation: Location? = null

    @Volatile
    private var pendingDestination: Pair<Double, Double>? = null

    @Volatile
    private var cachedRouteGeoJson: String? = null

    private var packetsSentCount: Long = 0L

    companion object {
        private const val REQ_PERMISSIONS = 201
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

        btnStartDashboard.setOnClickListener {
            stopCommanderStreaming()
            startActivity(Intent(this, DashboardActivity::class.java))
        }

        btnStartCommander.setOnClickListener {
            if (isStreaming.get()) {
                stopCommanderStreaming()
            } else {
                ensurePermissionsAndStart()
            }
        }

        ensurePermissionsAndStart()
        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIncomingShareIntent(intent)
        }
    }

    private fun ensurePermissionsAndStart() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        if (fineGranted && btGranted) {
            populatePairedDevices()
            if (!isStreaming.get()) {
                startCommanderStreaming()
            }
        } else {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun populatePairedDevices() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bonded = btManager?.adapter?.bondedDevices?.toList() ?: emptyList()
        pairedDevicesList = bonded

        val labels = if (bonded.isEmpty()) {
            listOf("No paired Bluetooth devices found")
        } else {
            bonded.map { "${it.name ?: "Unknown"} (${it.address})" }
        }
        spinnerPairedDevices.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )

        val preferredAddress = intent?.getStringExtra(TelemetryProtocol.EXTRA_DEVICE_ADDRESS)
            ?: getSharedPreferences(TelemetryProtocol.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(TelemetryProtocol.PREF_KEY_DEVICE_ADDRESS, null)

        if (preferredAddress != null) {
            val matchIndex = bonded.indexOfFirst { it.address == preferredAddress }
            if (matchIndex >= 0) {
                spinnerPairedDevices.setSelection(matchIndex)
            }
        }
    }

    private fun handleIncomingShareIntent(incomingIntent: Intent?) {
        if (incomingIntent == null) return
        if (incomingIntent.action == Intent.ACTION_SEND && incomingIntent.type?.startsWith("text/") == true) {
            val sharedText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            tvCommanderStatus.text = "Status: Processing shared Google Maps link..."
            Thread {
                val targetCoords = resolveGoogleMapsShareToCoordinates(sharedText)
                if (targetCoords != null) {
                    val (destLat, destLon) = targetCoords
                    runOnUiThread {
                        tvCommanderStatus.text = String.format(
                            Locale.US,
                            "Status: Destination extracted (%.5f, %.5f) • Fetching OSRM route...",
                            destLat,
                            destLon
                        )
                    }
                    requestOsrmRouteWhenLocationReady(destLat, destLon)
                } else {
                    runOnUiThread {
                        tvCommanderStatus.text =
                            "Status: Could not extract coordinates from shared link: $sharedText"
                    }
                }
            }.start()
        }
    }

    private fun resolveGoogleMapsShareToCoordinates(sharedText: String): Pair<Double, Double>? {
        extractLatLonFromString(sharedText)?.let { return it }

        val urlRegex = "(" + "http" + "s?://[^\\s\"<>]+)"
        val urlMatcher = Pattern.compile(urlRegex).matcher(sharedText)
        if (!urlMatcher.find()) return null
        var currentUrl = urlMatcher.group(1) ?: return null

        try {
            var redirectsRemaining = 10
            var htmlSnippet = ""

            while (redirectsRemaining > 0) {
                extractLatLonFromString(currentUrl)?.let { return it }

                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                }

                val code = conn.responseCode
                if (code in 300..399) {
                    val locationHeader = conn.getHeaderField("Location") ?: break
                    val resolvedUrl = URL(URL(currentUrl), locationHeader).toString()
                    conn.disconnect()
                    currentUrl = resolvedUrl
                    extractLatLonFromString(currentUrl)?.let { return it }
                    redirectsRemaining--
                } else {
                    val finalUrl = conn.url?.toString() ?: currentUrl
                    extractLatLonFromString(finalUrl)?.let {
                        conn.disconnect()
                        return it
                    }
                    htmlSnippet = try {
                        conn.inputStream.bufferedReader().use { reader ->
                            val buf = CharArray(32768)
                            val readCount = reader.read(buf)
                            if (readCount > 0) String(buf, 0, readCount) else ""
                        }
                    } catch (_: Exception) {
                        ""
                    }
                    conn.disconnect()
                    break
                }
            }

            extractLatLonFromString(currentUrl)?.let { return it }
            if (htmlSnippet.isNotEmpty()) {
                extractLatLonFromString(htmlSnippet)?.let { return it }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun extractLatLonFromString(input: String): Pair<Double, Double>? {
        val decoded = try {
            URLDecoder.decode(input, "UTF-8")
        } catch (_: Exception) {
            input
        }

        val patterns = listOf(
            Pattern.compile("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)"),
            Pattern.compile("@(-?\\d+\\.\\d+),\\s*(-?\\d+\\.\\d+)"),
            Pattern.compile("[?&](?:q|ll|query|center|destination|daddr)=(-?\\d+\\.\\d+),\\s*\\+?(-?\\d+\\.\\d+)"),
            Pattern.compile("/(?:search|place|dir)/(-?\\d+\\.\\d+),\\s*\\+?(-?\\d+\\.\\d+)"),
            Pattern.compile("\\[null,null,(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)\\]")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(decoded)
            if (matcher.find()) {
                val lat = matcher.group(1)?.toDoubleOrNull()
                val lon = matcher.group(2)?.toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    return Pair(lat, lon)
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun requestOsrmRouteWhenLocationReady(destLat: Double, destLon: Double) {
        val current = latestLocation
        if (current != null) {
            fetchAndTransmitOsrmRoute(current.latitude, current.longitude, destLat, destLon)
            return
        }

        pendingDestination = Pair(destLat, destLon)
        fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
            if (lastLoc != null && pendingDestination != null) {
                val target = pendingDestination ?: return@addOnSuccessListener
                pendingDestination = null
                latestLocation = lastLoc
                fetchAndTransmitOsrmRoute(lastLoc.latitude, lastLoc.longitude, target.first, target.second)
            }
        }
    }

    private fun fetchAndTransmitOsrmRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ) {
        Thread {
            val scheme = "http"
            val osrmEndpoint = "router.project-osrm.org/route/v1/driving"
            val osrmUrl = String.format(
                Locale.US,
                "%s://%s/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                scheme,
                osrmEndpoint,
                startLon,
                startLat,
                endLon,
                endLat
            )

            try {
                val conn = (URL(osrmUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val rootJson = JSONObject(responseBody)
                val routes = rootJson.optJSONArray("routes")
                if (routes == null || routes.length() == 0) {
                    runOnUiThread {
                        tvCommanderStatus.text = "Status: OSRM returned no route."
                    }
                    return@Thread
                }

                val firstRoute = routes.getJSONObject(0)
                val geometryObject = firstRoute.getJSONObject("geometry")
                val distanceMeters = firstRoute.optDouble("distance", 0.0)

                val featureCollectionJson = JSONObject().apply {
                    put("type", "FeatureCollection")
                    val feature = JSONObject().apply {
                        put("type", "Feature")
                        put("properties", JSONObject())
                        put("geometry", geometryObject)
                    }
                    put("features", JSONArray().put(feature))
                }.toString()

                cachedRouteGeoJson = featureCollectionJson

                val out = rfcommDataOut
                var sentImmediately = false
                if (out != null) {
                    try {
                        TelemetryProtocol.writeRouteDataPacket(out, featureCollectionJson)
                        sentImmediately = true
                    } catch (_: IOException) {
                        closeCommanderSocket()
                    }
                }

                runOnUiThread {
                    tvCommanderStatus.text = String.format(
                        Locale.US,
                        "Status: OSRM Route (%.1f km) %s",
                        distanceMeters / 1000.0,
                        if (sentImmediately) "BEAMED TO PHONE 2!" else "CACHED (Waiting BT connection)"
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvCommanderStatus.text = "Status: OSRM fetch error (${e.message})"
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun startCommanderStreaming() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth on Phone 1.", Toast.LENGTH_LONG).show()
            return
        }

        if (pairedDevicesList.isEmpty()) populatePairedDevices()
        val idx = spinnerPairedDevices.selectedItemPosition
        val targetDevice = if (idx in pairedDevicesList.indices) {
            pairedDevicesList[idx]
        } else {
            pairedDevicesList.firstOrNull()
        }

        isStreaming.set(true)
        btnStartCommander.text = "Stop Commander (Phone 1)"

        if (targetDevice != null) {
            getSharedPreferences(TelemetryProtocol.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(TelemetryProtocol.PREF_KEY_DEVICE_ADDRESS, targetDevice.address)
                .apply()

            Thread {
                while (isStreaming.get()) {
                    if (rfcommDataOut == null) {
                        try {
                            btAdapter.cancelDiscovery()
                            val socket = targetDevice.createRfcommSocketToServiceRecord(
                                TelemetryProtocol.SERVICE_UUID
                            )
                            socket.connect()
                            rfcommSocket = socket
                            val dataOut = DataOutputStream(socket.outputStream)
                            rfcommDataOut = dataOut

                            val existingRoute = cachedRouteGeoJson
                            if (existingRoute != null) {
                                TelemetryProtocol.writeRouteDataPacket(dataOut, existingRoute)
                            }

                            runOnUiThread {
                                tvCommanderStatus.text =
                                    "Status: Connected to ${targetDevice.name ?: targetDevice.address} • Streaming 1 Hz GPS + Route"
                            }
                        } catch (_: IOException) {
                            closeCommanderSocket()
                            try {
                                Thread.sleep(2000L)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    } else {
                        try {
                            Thread.sleep(500L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }.start()
        } else {
            tvCommanderStatus.text = "Status: GPS active (Pair Phone 2 in Bluetooth Settings)"
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                latestLocation = loc

                val pending = pendingDestination
                if (pending != null) {
                    pendingDestination = null
                    fetchAndTransmitOsrmRoute(loc.latitude, loc.longitude, pending.first, pending.second)
                }

                val frame = TelemetryProtocol.TelemetryFrame(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    bearing = loc.bearing,
                    speed = loc.speed
                )
                sendGpsTelemetryFrame(frame)
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        )
    }

    private fun sendGpsTelemetryFrame(frame: TelemetryProtocol.TelemetryFrame) {
        Thread {
            val out = rfcommDataOut
            var delivered = false
            if (out != null) {
                try {
                    TelemetryProtocol.writeGpsPacket(out, frame)
                    packetsSentCount++
                    delivered = true
                } catch (_: IOException) {
                    closeCommanderSocket()
                }
            }

            runOnUiThread {
                tvTelemetryReadout.text = String.format(
                    Locale.US,
                    "1 Hz GPS Telemetry #%d (%s)\n" +
                        "LAT : %.6f\n" +
                        "LON : %.6f\n" +
                        "BRG : %.1f deg\n" +
                        "SPD : %.2f m/s (%.1f km/h)\n" +
                        "ROUTE : %s",
                    packetsSentCount,
                    if (delivered) "SENT OVER RFCOMM" else "WAITING BT",
                    frame.latitude,
                    frame.longitude,
                    frame.bearing,
                    frame.speed,
                    frame.speed * 3.6f,
                    if (cachedRouteGeoJson != null) "ACTIVE (OSRM GeoJSON Ready)" else "Share a place from Google Maps"
                )
            }
        }.start()
    }

    private fun stopCommanderStreaming() {
        isStreaming.set(false)
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        closeCommanderSocket()
        btnStartCommander.text = "Start as Commander (Phone 1)"
        tvCommanderStatus.text = "Status: Commander stopped."
    }

    private fun closeCommanderSocket() {
        try {
            rfcommDataOut?.close()
        } catch (_: IOException) {
        }
        try {
            rfcommSocket?.close()
        } catch (_: IOException) {
        }
        rfcommDataOut = null
        rfcommSocket = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            ensurePermissionsAndStart()
        }
    }

    override fun onDestroy() {
        stopCommanderStreaming()
        super.onDestroy()
    }
}
