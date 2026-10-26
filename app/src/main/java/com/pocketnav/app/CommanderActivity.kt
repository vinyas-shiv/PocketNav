package com.pocketnav.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.math.*

class CommanderForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startPocketNotification()
        acquireCpuWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPocketNotification()
        return START_STICKY
    }

    private fun startPocketNotification() {
        val channelId = "pocketnav_commander_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "PocketNav Pocket Telemetry", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps GPS and Bluetooth streaming active while phone is locked in pocket"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, CommanderActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PocketNav Commander (Pocket Mode)")
            .setContentText("Streaming low-power GPS telemetry to Dashboard")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1001, notif)
            }
        } catch (_: Throwable) {}
    }

    private fun acquireCpuWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketNav::PocketGpsLock").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class CommanderActivity : AppCompatActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var wakeLock: PowerManager.WakeLock? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var rfcommSocket: BluetoothSocket? = null
    private var dataOutputStream: DataOutputStream? = null
    private val isStreaming = AtomicBoolean(false)
    private val isUiVisible = AtomicBoolean(true)
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val routeExecutor = Executors.newSingleThreadExecutor()

    private lateinit var deviceSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var telemetryText: TextView
    private lateinit var routeStatusText: TextView
    private lateinit var shareUrlInput: EditText
    private lateinit var sendRouteButton: Button
    private lateinit var batteryOptButton: Button
    private lateinit var toggleStreamButton: Button
    private val pairedDevicesList = mutableListOf<BluetoothDevice>()

    @Volatile private var latestLatitude: Double = Double.NaN
    @Volatile private var latestLongitude: Double = Double.NaN
    @Volatile private var lastSentLat: Double = Double.NaN
    @Volatile private var lastSentLon: Double = Double.NaN
    @Volatile private var lastSentBearing: Float = 0f
    @Volatile private var lastSentTimeMs: Long = 0L

    @Volatile private var pendingDestLat: Double = Double.NaN
    @Volatile private var pendingDestLon: Double = Double.NaN
    @Volatile private var pendingDestName: String = "Destination"
    @Volatile private var cachedRouteEnvelopeJson: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acquireWakeLockSafely()
        startPocketForegroundService()
        setupCommanderUi()
        bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        ensureRuntimePermissionsAndStart()
        handleIncomingShareIntent(intent)
    }

    override fun onResume() { super.onResume(); isUiVisible.set(true) }
    override fun onPause() { super.onPause(); isUiVisible.set(false) }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun startPocketForegroundService() {
        try {
            val svc = Intent(this, CommanderForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        } catch (_: Throwable) {}
    }

    private fun stopPocketForegroundService() {
        try { stopService(Intent(this, CommanderForegroundService::class.java)) } catch (_: Throwable) {}
    }

    private fun setupCommanderUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF0F172A.toInt()); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 40, 40, 40); setBackgroundColor(0xFF0F172A.toInt())
        }
        val title = TextView(this).apply { text = "Commander (Phone 1 • Pocket Mode)"; textSize = 20f; setTextColor(0xFFF8FAFC.toInt()); setPadding(0, 0, 0, 6) }
        val subtitle = TextView(this).apply { text = "Streams 1 Hz GPS & Route with Screen OFF in Pocket:"; textSize = 13f; setTextColor(0xFF94A3B8.toInt()); setPadding(0, 0, 0, 14) }
        deviceSpinner = Spinner(this).apply { setBackgroundColor(0xFF1E293B.toInt()); setPadding(20, 16, 20, 16) }
        toggleStreamButton = Button(this).apply {
            text = "Connect & Start Pocket Streaming"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF2563EB.toInt()); setPadding(24, 20, 24, 20)
            setOnClickListener { if (isStreaming.get()) stopStreaming() else startStreamingToSelectedDevice() }
        }
        batteryOptButton = Button(this).apply {
            text = "Enable Unrestricted Pocket Battery Mode"; textSize = 12f; setTextColor(0xFFFDE047.toInt()); setBackgroundColor(0xFF1E293B.toInt()); setPadding(20, 14, 20, 14)
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        }
        statusText = TextView(this).apply { text = "Status: Ready (Pocket Foreground Service Active)"; textSize = 13f; setTextColor(0xFF38BDF8.toInt()); setPadding(0, 14, 0, 6) }
        routeStatusText = TextView(this).apply { text = "Route Hand-off: Share a place from Google Maps or paste a link/coordinates below."; textSize = 12f; setTextColor(0xFFA7F3D0.toInt()); setPadding(0, 4, 0, 10) }
        shareUrlInput = EditText(this).apply {
            hint = "Paste Google Maps share link or lat,lon (e.g. 12.9716,77.5946)"; setHintTextColor(0xFF64748B.toInt())
            setTextColor(0xFFF8FAFC.toInt()); setBackgroundColor(0xFF1E293B.toInt()); textSize = 13f; setPadding(20, 16, 20, 16)
        }
        sendRouteButton = Button(this).apply {
            text = "Fetch OSRM Route & Beam to Phone 2"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF059669.toInt()); setPadding(20, 18, 20, 18)
            setOnClickListener {
                val input = shareUrlInput.text?.toString()?.trim().orEmpty()
                if (input.isNotEmpty()) processSharedLocationText(input)
                else Toast.makeText(this@CommanderActivity, "Paste a Google Maps link or coordinates first.", Toast.LENGTH_SHORT).show()
            }
        }
        telemetryText = TextView(this).apply { text = "GPS: Acquiring fix (UI updates pause automatically when screen is off)..."; textSize = 12f; setTextColor(0xFFCBD5E1.toInt()); setPadding(0, 14, 0, 0) }
        fun lp(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
        root.addView(title); root.addView(subtitle); root.addView(deviceSpinner)
        root.addView(toggleStreamButton, lp(16)); root.addView(batteryOptButton, lp(10))
        root.addView(statusText); root.addView(routeStatusText); root.addView(shareUrlInput)
        root.addView(sendRouteButton, lp(12)); root.addView(telemetryText)
        scroll.addView(root); setContentView(scroll)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") })
            } else {
                Toast.makeText(this, "PocketNav is already unrestricted for pocket background use!", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
            Toast.makeText(this, "Battery optimization exemption granted or managed by system.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureRuntimePermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION); needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 201)
        populatePairedDevices(); startHighAccuracyLocationUpdates()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        populatePairedDevices(); startHighAccuracyLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun populatePairedDevices() {
        pairedDevicesList.clear()
        val bonded = try { bluetoothAdapter?.bondedDevices ?: emptySet() } catch (_: Throwable) { emptySet() }
        pairedDevicesList.addAll(bonded)
        val names = if (pairedDevicesList.isEmpty()) listOf("No paired Bluetooth devices found") else pairedDevicesList.map { "${it.name ?: "Unknown"} (${it.address})" }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val lat = loc.latitude; val lon = loc.longitude
                val bearing = if (loc.hasBearing()) loc.bearing else lastSentBearing
                val speed = if (loc.hasSpeed()) loc.speed else 0.0f
                latestLatitude = lat; latestLongitude = lon

                if (isUiVisible.get()) {
                    runOnUiThread {
                        telemetryText.text = String.format(Locale.US, "GPS Fix (Pocket Service Active):\nLat: %.6f | Lon: %.6f\nBearing: %.1f° | Speed: %.1f km/h", lat, lon, bearing, speed * 3.6f)
                    }
                }
                if (!pendingDestLat.isNaN() && !pendingDestLon.isNaN()) {
                    val dLat = pendingDestLat; val dLon = pendingDestLon; val dName = pendingDestName
                    pendingDestLat = Double.NaN; pendingDestLon = Double.NaN
                    fetchRouteAndTransmit(lat, lon, dLat, dLon, dName)
                }
                if (isStreaming.get()) {
                    val now = System.currentTimeMillis()
                    val moved = if (lastSentLat.isNaN()) 999.0 else quickDistMeters(lat, lon, lastSentLat, lastSentLon)
                    val bDelta = abs(bearing - lastSentBearing)
                    if (speed >= 0.45f || moved >= 1.2 || bDelta >= 3.5f || (now - lastSentTimeMs) >= 3000L) {
                        lastSentLat = lat; lastSentLon = lon; lastSentBearing = bearing; lastSentTimeMs = now
                        ioExecutor.execute { sendLocationPacket(lat, lon, bearing, speed) }
                    }
                }
            }
        }
    }

    private fun quickDistMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * 111320.0
        val dLon = (lon2 - lon1) * 111320.0 * cos(Math.toRadians(lat1))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    private fun handleIncomingShareIntent(incoming: Intent?) {
        if (incoming?.action == Intent.ACTION_SEND && incoming.type?.startsWith("text/") == true) {
            val combined = listOfNotNull(incoming.getStringExtra(Intent.EXTRA_SUBJECT), incoming.getStringExtra(Intent.EXTRA_TEXT)).joinToString(" ").trim()
            if (combined.isNotEmpty()) {
                shareUrlInput.setText(combined)
                processSharedLocationText(combined)
                if (!isStreaming.get() && pairedDevicesList.isNotEmpty()) startStreamingToSelectedDevice()
            }
        }
    }

    private fun processSharedLocationText(sharedText: String) {
        runOnUiThread { routeStatusText.text = "Route Hand-off: Extracting destination..." }
        routeExecutor.execute {
            try {
                val extracted = resolveDestinationFromSharedText(sharedText)
                if (extracted == null) {
                    runOnUiThread { routeStatusText.text = "Route Hand-off Error: Could not extract coordinates from link." }
                    return@execute
                }
                val (destLat, destLon, destName) = extracted
                if (latestLatitude.isNaN() || latestLongitude.isNaN()) {
                    pendingDestLat = destLat; pendingDestLon = destLon; pendingDestName = destName
                    runOnUiThread { routeStatusText.text = String.format(Locale.US, "Destination '%s' locked (%.5f, %.5f). Waiting for GPS fix...", destName, destLat, destLon) }
                    tryLastLocationFallback()
                } else {
                    fetchRouteAndTransmit(latestLatitude, latestLongitude, destLat, destLon, destName)
                }
            } catch (e: Exception) {
                runOnUiThread { routeStatusText.text = "Route Hand-off Error: ${e.message}" }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryLastLocationFallback() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && latestLatitude.isNaN()) {
                    latestLatitude = loc.latitude; latestLongitude = loc.longitude
                    if (!pendingDestLat.isNaN() && !pendingDestLon.isNaN()) {
                        val dLat = pendingDestLat; val dLon = pendingDestLon; val dName = pendingDestName
                        pendingDestLat = Double.NaN; pendingDestLon = Double.NaN
                        fetchRouteAndTransmit(loc.latitude, loc.longitude, dLat, dLon, dName)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun resolveDestinationFromSharedText(sharedText: String): Triple<Double, Double, String>? {
        var label = extractCleanPlaceTitle(sharedText)
        extractLatLonFromString(sharedText)?.let { return Triple(it.first, it.second, label.ifEmpty { "Shared Pin" }) }
        val urlRegex = "(" + "http" + "s?://[^\\s\"<>]+)"
        val m = Pattern.compile(urlRegex).matcher(sharedText)
        if (!m.find()) return null
        var currentUrl = m.group(1) ?: return null
        var redirects = 0; var htmlBody = ""
        while (redirects < 10) {
            extractPlaceNameFromUrl(currentUrl)?.let { if (it.isNotEmpty()) label = it }
            extractLatLonFromString(currentUrl)?.let { return Triple(it.first, it.second, label.ifEmpty { "Destination" }) }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 10000; readTimeout = 10000; requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/124.0.0.0 Mobile")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val locHeader = conn.getHeaderField("Location") ?: break
                currentUrl = URL(URL(currentUrl), locHeader).toString()
                conn.disconnect(); redirects++
            } else {
                val finalUrl = conn.url?.toString() ?: currentUrl
                extractPlaceNameFromUrl(finalUrl)?.let { if (it.isNotEmpty()) label = it }
                extractLatLonFromString(finalUrl)?.let { conn.disconnect(); return Triple(it.first, it.second, label.ifEmpty { "Destination" }) }
                htmlBody = readStreamSnippet(conn, 120_000)
                conn.disconnect(); break
            }
        }
        if (htmlBody.isNotEmpty()) {
            val og = Pattern.compile("property=\"og:title\"\\s+content=\"([^\"]+)\"").matcher(htmlBody)
            if (og.find()) {
                val t = og.group(1)?.replace(" - Google Maps", "")?.trim().orEmpty()
                if (t.isNotEmpty() && !t.equals("Google Maps", true)) label = t.take(48)
            }
            extractLatLonFromString(htmlBody)?.let { return Triple(it.first, it.second, label.ifEmpty { "Destination" }) }
        }
        val query = extractPlaceQuery(currentUrl, sharedText)
        if (!query.isNullOrBlank()) {
            if (label.isEmpty()) label = query
            geocodeViaNominatim(query)?.let { return Triple(it.first, it.second, label.ifEmpty { query }) }
        }
        return null
    }

    private fun extractCleanPlaceTitle(sharedText: String): String {
        val urlRegex = "(" + "http" + "s?://[^\\s\"<>]+)"
        val lines = sharedText.replace(Regex(urlRegex), "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val c = lines.firstOrNull()?.take(48)?.trim().orEmpty()
        return if (c.length >= 2 && !c.matches(Regex("^[-+]?\\d+.*"))) c else ""
    }

    private fun extractPlaceNameFromUrl(urlStr: String): String? = try {
        val decoded = URLDecoder.decode(urlStr, "UTF-8")
        val m = Pattern.compile("/maps/(?:place|search)/([^/@?]+)").matcher(decoded)
        if (m.find()) m.group(1)?.replace('+', ' ')?.trim()?.takeIf { it.isNotEmpty() && !it.matches(Regex("^[-+]?\\d+\\.\\d+.*")) }?.take(48) else null
    } catch (_: Exception) { null }

    private fun extractLatLonFromString(input: String): Pair<Double, Double>? {
        val decoded = try { URLDecoder.decode(input, "UTF-8") } catch (_: Exception) { input }
        val patterns = listOf(
            "!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)",
            "@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)",
            "(?:q|ll|query|destination|center|daddr)=(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)",
            "/(?:place|search)/(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)",
            "\\[null,null,(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)\\]",
            "(?<![\\d.])(-?\\d{1,2}\\.\\d{4,})\\s*,\\s*(-?\\d{1,3}\\.\\d{4,})(?![\\d.])"
        )
        for (p in patterns) {
            val m = Pattern.compile(p).matcher(decoded)
            if (m.find()) {
                val lat = m.group(1)?.toDoubleOrNull(); val lon = m.group(2)?.toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) return Pair(lat, lon)
            }
        }
        return null
    }

    private fun extractPlaceQuery(resolvedUrl: String, sharedText: String): String? {
        val dec = try { URLDecoder.decode(resolvedUrl, "UTF-8") } catch (_: Exception) { resolvedUrl }
        val pm = Pattern.compile("/maps/(?:place|search)/([^/?&]+)").matcher(dec)
        if (pm.find()) pm.group(1)?.replace('+', ' ')?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val qm = Pattern.compile("[?&](?:q|query|daddr)=([^&]+)").matcher(dec)
        if (qm.find()) qm.group(1)?.replace('+', ' ')?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val urlRegex = "(" + "http" + "s?://[^\\s\"<>]+)"
        return sharedText.replace(Regex(urlRegex), "").lines().firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun geocodeViaNominatim(query: String): Pair<Double, Double>? = try {
        val enc = URLEncoder.encode(query, "UTF-8")
        val scheme = "https"
        val conn = (URL("$scheme://nominatim.openstreetmap.org/search?format=json&limit=1&q=$enc").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000; setRequestProperty("User-Agent", "PocketNavAndroid/1.0")
        }
        val arr = JSONArray(readStreamSnippet(conn, 32_000))
        conn.disconnect()
        if (arr.length() > 0) {
            val o = arr.getJSONObject(0)
            val lat = o.optString("lat").toDoubleOrNull(); val lon = o.optString("lon").toDoubleOrNull()
            if (lat != null && lon != null) Pair(lat, lon) else null
        } else null
    } catch (_: Exception) { null }

    private fun fetchRouteAndTransmit(startLat: Double, startLon: Double, endLat: Double, endLon: Double, destinationName: String) {
        runOnUiThread { routeStatusText.text = String.format(Locale.US, "Fetching OSRM route to %s...", destinationName) }
        routeExecutor.execute {
            try {
                val scheme = "https"
                val osrmUrl = String.format(Locale.US, "%s://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true", scheme, startLon, startLat, endLon, endLat)
                val conn = (URL(osrmUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000; readTimeout = 12000; setRequestProperty("User-Agent", "PocketNavAndroid/1.0")
                }
                val body = readStreamSnippet(conn, 600_000)
                conn.disconnect()
                val routes = JSONObject(body).optJSONArray("routes")
                if (routes == null || routes.length() == 0) {
                    runOnUiThread { routeStatusText.text = "OSRM Error: No driving route found." }
                    return@execute
                }
                val firstRoute = routes.getJSONObject(0)
                val totalDist = firstRoute.optDouble("distance", 0.0)
                val totalDur = firstRoute.optDouble("duration", 0.0)
                val rawCoords = firstRoute.getJSONObject("geometry").optJSONArray("coordinates") ?: JSONArray()
                val anchored = JSONArray().put(JSONArray().put(startLon).put(startLat))
                for (i in 0 until rawCoords.length()) anchored.put(rawCoords.getJSONArray(i))
                val routeFc = JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(
                    JSONObject().put("type", "Feature").put("properties", JSONObject()).put("geometry", JSONObject().put("type", "LineString").put("coordinates", anchored))
                ))
                val stepsArr = JSONArray(); val turnFeatures = JSONArray()
                var resolvedName = destinationName
                val legs = firstRoute.optJSONArray("legs")
                if (legs != null && legs.length() > 0) {
                    val leg0 = legs.getJSONObject(0)
                    val summary = leg0.optString("summary", "")
                    if ((resolvedName == "Destination" || resolvedName == "Shared Pin") && summary.isNotBlank()) {
                        resolvedName = summary.split(",").last().trim().ifEmpty { resolvedName }
                    }
                    val steps = leg0.optJSONArray("steps")
                    if (steps != null) {
                        for (i in 0 until steps.length()) {
                            val s = steps.getJSONObject(i)
                            val man = s.optJSONObject("maneuver") ?: continue
                            val loc = man.optJSONArray("location") ?: continue
                            if (loc.length() < 2) continue
                            val sLon = loc.optDouble(0); val sLat = loc.optDouble(1)
                            val type = man.optString("type", "turn")
                            val mod = man.optString("modifier", "straight")
                            val rName = s.optString("name", "").ifBlank { s.optString("ref", "road ahead") }
                            val sDist = s.optDouble("distance", 0.0)
                            stepsArr.put(JSONObject().put("lat", sLat).put("lon", sLon).put("type", type).put("modifier", mod).put("name", rName).put("distance", sDist))
                            if (type != "depart" && type != "arrive") {
                                turnFeatures.put(JSONObject().put("type", "Feature").put("properties", JSONObject().put("name", rName)).put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(sLon).put(sLat))))
                            }
                        }
                    }
                }
                val turnsFc = JSONObject().put("type", "FeatureCollection").put("features", turnFeatures)
                val destFc = JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(
                    JSONObject().put("type", "Feature").put("properties", JSONObject().put("title", resolvedName)).put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(endLon).put(endLat)))
                ))
                val envelopeStr = JSONObject().apply {
                    put("routeGeoJson", routeFc.toString()); put("turnsGeoJson", turnsFc.toString()); put("destGeoJson", destFc.toString())
                    put("destinationName", resolvedName); put("destLat", endLat); put("destLon", endLon)
                    put("totalDistanceMeters", totalDist); put("totalDurationSeconds", totalDur); put("steps", stepsArr)
                }.toString()
                cachedRouteEnvelopeJson = envelopeStr
                val distKm = totalDist / 1000.0; val durMin = (totalDur / 60.0).toInt().coerceAtLeast(1)
                if (isStreaming.get()) {
                    ioExecutor.execute { sendRouteDataPacket(envelopeStr) }
                    runOnUiThread { routeStatusText.text = String.format(Locale.US, "Route Beamed to Phone 2! (%s • %.1f km • ~%d min)", resolvedName, distKm, durMin) }
                } else {
                    runOnUiThread { routeStatusText.text = String.format(Locale.US, "Route Ready (%s • %.1f km • ~%d min). Connect Phone 2 to beam.", resolvedName, distKm, durMin) }
                }
            } catch (e: Exception) {
                runOnUiThread { routeStatusText.text = "OSRM Error: ${e.message}" }
            }
        }
    }

    private fun readStreamSnippet(conn: HttpURLConnection, maxChars: Int): String {
        val stream = try { conn.inputStream } catch (_: Exception) { conn.errorStream } ?: return ""
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        val sb = StringBuilder(); val buf = CharArray(4096); var n: Int
        while (reader.read(buf).also { n = it } != -1) { sb.append(buf, 0, n); if (sb.length >= maxChars) break }
        reader.close()
        return sb.toString()
    }

    @SuppressLint("MissingPermission")
    private fun startHighAccuracyLocationUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return
        try {
            val priority = if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            val req = LocationRequest.Builder(priority, 1000L).setMinUpdateIntervalMillis(1000L).setMaxUpdateDelayMillis(1000L).setWaitForAccurateLocation(false).build()
            fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (e: Throwable) {
            statusText.text = "Location warning: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startStreamingToSelectedDevice() {
        if (pairedDevicesList.isEmpty()) {
            populatePairedDevices()
            if (pairedDevicesList.isEmpty()) return
        }
        val idx = deviceSpinner.selectedItemPosition
        if (idx !in pairedDevicesList.indices) return
        val dev = pairedDevicesList[idx]
        startPocketForegroundService()
        statusText.text = "Status: Connecting to ${dev.name}..."
        toggleStreamButton.isEnabled = false
        ioExecutor.execute {
            try {
                try { bluetoothAdapter?.cancelDiscovery() } catch (_: SecurityException) {}
                val sock = dev.createRfcommSocketToServiceRecord(TelemetryProtocol.SERVICE_UUID)
                sock.connect()
                rfcommSocket = sock; dataOutputStream = DataOutputStream(sock.outputStream); isStreaming.set(true)
                runOnUiThread {
                    statusText.text = "Status: Connected & Pocket Streaming to ${dev.name} (Safe to lock screen)"
                    toggleStreamButton.text = "Stop Streaming"; toggleStreamButton.setBackgroundColor(0xFFDC2626.toInt()); toggleStreamButton.isEnabled = true
                }
                cachedRouteEnvelopeJson?.let { sendRouteDataPacket(it) }
            } catch (e: Throwable) {
                closeSocketQuietly()
                runOnUiThread {
                    statusText.text = "Status: Connection failed (${e.message})"
                    toggleStreamButton.text = "Connect & Start Pocket Streaming"; toggleStreamButton.setBackgroundColor(0xFF2563EB.toInt()); toggleStreamButton.isEnabled = true
                }
            }
        }
    }

    @Synchronized private fun sendLocationPacket(lat: Double, lon: Double, bearing: Float, speed: Float) {
        val out = dataOutputStream ?: return
        try { TelemetryProtocol.writeLocationPacket(out, lat, lon, bearing, speed) } catch (e: IOException) { stopStreamingOnDisconnect(e.message) }
    }

    @Synchronized private fun sendRouteDataPacket(json: String) {
        val out = dataOutputStream ?: return
        try { TelemetryProtocol.writeRouteDataPacket(out, json) } catch (e: IOException) { stopStreamingOnDisconnect(e.message) }
    }

    private fun stopStreamingOnDisconnect(reason: String?) {
        isStreaming.set(false); closeSocketQuietly()
        runOnUiThread {
            statusText.text = "Status: Disconnected ($reason)"
            toggleStreamButton.text = "Connect & Start Pocket Streaming"; toggleStreamButton.setBackgroundColor(0xFF2563EB.toInt())
        }
    }

    private fun stopStreaming() {
        isStreaming.set(false)
        stopPocketForegroundService()
        ioExecutor.execute {
            closeSocketQuietly()
            runOnUiThread {
                statusText.text = "Status: Streaming stopped."
                toggleStreamButton.text = "Connect & Start Pocket Streaming"; toggleStreamButton.setBackgroundColor(0xFF2563EB.toInt())
            }
        }
    }

    private fun closeSocketQuietly() {
        try { dataOutputStream?.close() } catch (_: IOException) {}
        try { rfcommSocket?.close() } catch (_: IOException) {}
        dataOutputStream = null; rfcommSocket = null
    }

    private fun acquireWakeLockSafely() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketNav::CommanderWakeLock").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreaming.set(false)
        stopPocketForegroundService()
        try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (_: Throwable) {}
        closeSocketQuietly()
        ioExecutor.shutdownNow(); routeExecutor.shutdownNow()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
    }
}
