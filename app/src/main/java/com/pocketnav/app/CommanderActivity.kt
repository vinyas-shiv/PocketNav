package com.pocketnav.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
import kotlin.math.max
import kotlin.math.min

class CommanderActivity : AppCompatActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var wakeLock: PowerManager.WakeLock? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var rfcommSocket: BluetoothSocket? = null
    private var dataOutputStream: DataOutputStream? = null
    private val isStreaming = AtomicBoolean(false)
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val routeExecutor = Executors.newSingleThreadExecutor()

    private lateinit var deviceSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var telemetryText: TextView
    private lateinit var routeStatusText: TextView
    private lateinit var shareUrlInput: EditText
    private lateinit var sendRouteButton: Button
    private lateinit var toggleStreamButton: Button
    private val pairedDevicesList = mutableListOf<BluetoothDevice>()

    @Volatile private var latestLatitude: Double = Double.NaN
    @Volatile private var latestLongitude: Double = Double.NaN
    @Volatile private var pendingDestLat: Double = Double.NaN
    @Volatile private var pendingDestLon: Double = Double.NaN
    @Volatile private var pendingDestName: String = "Destination"
    @Volatile private var cachedRouteEnvelopeJson: String? = null
    @Volatile private var cachedMapRoadsEnvelopeJson: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        acquireWakeLockSafely()
        setupCommanderUi()
        bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        ensureRuntimePermissionsAndStart()
        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun setupCommanderUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0F172A.toInt())
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFF0F172A.toInt())
        }
        val title = TextView(this).apply {
            text = "Commander (Phone 1)"
            textSize = 22f
            setTextColor(0xFFF8FAFC.toInt())
            setPadding(0, 0, 0, 8)
        }
        val subtitle = TextView(this).apply {
            text = "Select paired Dashboard (Phone 2) for 1 Hz GPS + Route + Roads:"
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, 0, 0, 16)
        }
        deviceSpinner = Spinner(this).apply {
            setBackgroundColor(0xFF1E293B.toInt())
            setPadding(24, 20, 24, 20)
        }
        toggleStreamButton = Button(this).apply {
            text = "Connect & Start Streaming"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2563EB.toInt())
            setPadding(32, 24, 32, 24)
            setOnClickListener { if (isStreaming.get()) stopStreaming() else startStreamingToSelectedDevice() }
        }
        statusText = TextView(this).apply {
            text = "Status: Waiting for connection..."
            textSize = 14f
            setTextColor(0xFF38BDF8.toInt())
            setPadding(0, 20, 0, 8)
        }
        routeStatusText = TextView(this).apply {
            text = "Route Hand-off: Share a place from Google Maps or paste a link/coordinates below."
            textSize = 13f
            setTextColor(0xFFA7F3D0.toInt())
            setPadding(0, 8, 0, 12)
        }
        shareUrlInput = EditText(this).apply {
            hint = "Paste Google Maps share link or lat,lon (e.g. 12.9716,77.5946)"
            setHintTextColor(0xFF64748B.toInt())
            setTextColor(0xFFF8FAFC.toInt())
            setBackgroundColor(0xFF1E293B.toInt())
            textSize = 13f
            setPadding(24, 20, 24, 20)
        }
        sendRouteButton = Button(this).apply {
            text = "Fetch Route + Roads & Beam to Phone 2"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF059669.toInt())
            setPadding(24, 20, 24, 20)
            setOnClickListener {
                val input = shareUrlInput.text?.toString()?.trim().orEmpty()
                if (input.isNotEmpty()) processSharedLocationText(input)
                else Toast.makeText(this@CommanderActivity, "Paste a link or coordinates first.", Toast.LENGTH_SHORT).show()
            }
        }
        telemetryText = TextView(this).apply {
            text = "GPS: Acquiring high-accuracy fix..."
            textSize = 13f
            setTextColor(0xFFCBD5E1.toInt())
            setPadding(0, 16, 0, 0)
        }
        val lpToggle = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        val lpSend = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        root.addView(title)
        root.addView(subtitle)
        root.addView(deviceSpinner)
        root.addView(toggleStreamButton, lpToggle)
        root.addView(statusText)
        root.addView(routeStatusText)
        root.addView(shareUrlInput)
        root.addView(sendRouteButton, lpSend)
        root.addView(telemetryText)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun ensureRuntimePermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 201)
        }
        populatePairedDevices()
        startHighAccuracyLocationUpdates()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        populatePairedDevices()
        startHighAccuracyLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun populatePairedDevices() {
        pairedDevicesList.clear()
        val bonded = try {
            bluetoothAdapter?.bondedDevices ?: emptySet()
        } catch (_: SecurityException) {
            emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
        pairedDevicesList.addAll(bonded)
        val names = if (pairedDevicesList.isEmpty()) listOf("No paired Bluetooth devices found")
        else pairedDevicesList.map { "${it.name ?: "Unknown"} (${it.address})" }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val lat = loc.latitude
                val lon = loc.longitude
                val bearing = if (loc.hasBearing()) loc.bearing else 0.0f
                val speed = if (loc.hasSpeed()) loc.speed else 0.0f
                latestLatitude = lat; latestLongitude = lon
                runOnUiThread {
                    telemetryText.text = String.format(Locale.US, "GPS Fix:\nLat: %.6f\nLon: %.6f\nBearing: %.1f°\nSpeed: %.1f km/h", lat, lon, bearing, speed * 3.6f)
                }
                if (!pendingDestLat.isNaN() && !pendingDestLon.isNaN()) {
                    val dLat = pendingDestLat; val dLon = pendingDestLon; val dName = pendingDestName
                    pendingDestLat = Double.NaN; pendingDestLon = Double.NaN
                    fetchRouteAndRoadsAndTransmit(lat, lon, dLat, dLon, dName)
                }
                if (isStreaming.get()) ioExecutor.execute { sendLocationPacket(lat, lon, bearing, speed) }
            }
        }
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
                    fetchRouteAndRoadsAndTransmit(latestLatitude, latestLongitude, destLat, destLon, destName)
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
                        fetchRouteAndRoadsAndTransmit(loc.latitude, loc.longitude, dLat, dLon, dName)
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
        var redirects = 0
        var htmlBody = ""
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
                conn.disconnect()
                redirects++
            } else {
                val finalUrl = conn.url?.toString() ?: currentUrl
                extractPlaceNameFromUrl(finalUrl)?.let { if (it.isNotEmpty()) label = it }
                extractLatLonFromString(finalUrl)?.let { conn.disconnect(); return Triple(it.first, it.second, label.ifEmpty { "Destination" }) }
                htmlBody = readStreamSnippet(conn, 120_000)
                conn.disconnect()
                break
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
                val lat = m.group(1)?.toDoubleOrNull()
                val lon = m.group(2)?.toDoubleOrNull()
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

    private fun fetchRouteAndRoadsAndTransmit(startLat: Double, startLon: Double, endLat: Double, endLon: Double, destinationName: String) {
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
                var minLat = min(startLat, endLat); var maxLat = max(startLat, endLat)
                var minLon = min(startLon, endLon); var maxLon = max(startLon, endLon)
                for (i in 0 until rawCoords.length()) {
                    val pt = rawCoords.getJSONArray(i)
                    anchored.put(pt)
                    val lon = pt.optDouble(0, startLon); val lat = pt.optDouble(1, startLat)
                    if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                    if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
                }
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
                    put("routeGeoJson", routeFc.toString())
                    put("turnsGeoJson", turnsFc.toString())
                    put("destGeoJson", destFc.toString())
                    put("destinationName", resolvedName)
                    put("destLat", endLat); put("destLon", endLon)
                    put("totalDistanceMeters", totalDist); put("totalDurationSeconds", totalDur)
                    put("steps", stepsArr)
                }.toString()
                cachedRouteEnvelopeJson = envelopeStr
                val distKm = totalDist / 1000.0; val durMin = (totalDur / 60.0).toInt().coerceAtLeast(1)
                if (isStreaming.get()) {
                    ioExecutor.execute { sendRouteDataPacket(envelopeStr) }
                    runOnUiThread { routeStatusText.text = String.format(Locale.US, "Route Beamed! (%s • %.1f km • ~%d min). Fetching roads...", resolvedName, distKm, durMin) }
                } else {
                    runOnUiThread { routeStatusText.text = String.format(Locale.US, "Route Ready (%s • %.1f km • ~%d min).", resolvedName, distKm, durMin) }
                }
                fetchSurroundingRoadsEnvelope(startLat, startLon, minLat, minLon, maxLat, maxLon)?.let { roadsStr ->
                    cachedMapRoadsEnvelopeJson = roadsStr
                    if (isStreaming.get()) {
                        ioExecutor.execute { sendMapRoadsPacket(roadsStr) }
                        runOnUiThread { routeStatusText.text = String.format(Locale.US, "Route + Actual Roads Beamed! (%s • %.1f km • ~%d min)", resolvedName, distKm, durMin) }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { routeStatusText.text = "OSRM Error: ${e.message}" }
            }
        }
    }

    private fun fetchSurroundingRoadsEnvelope(startLat: Double, startLon: Double, rawMinLat: Double, rawMinLon: Double, rawMaxLat: Double, rawMaxLon: Double): String? {
        val pad = 0.0045
        var south = rawMinLat - pad; var north = rawMaxLat + pad
        var west = rawMinLon - pad; var east = rawMaxLon + pad
        val maxSpan = 0.075
        if (north - south > maxSpan) { south = min(startLat, rawMaxLat) - pad; north = south + maxSpan }
        if (east - west > maxSpan) { west = min(startLon, rawMaxLon) - pad; east = west + maxSpan }
        val q = String.format(Locale.US, "[out:json][timeout:12];way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)$\"](%.5f,%.5f,%.5f,%.5f);out geom;", south, west, north, east)
        val scheme = "https"
        val endpoints = listOf("$scheme://overpass-api.de/api/interpreter", "$scheme://overpass.kumi.systems/api/interpreter")
        var body = ""
        for (ep in endpoints) {
            try {
                val conn = (URL(ep).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 12000; requestMethod = "POST"; doOutput = true
                    setRequestProperty("User-Agent", "PocketNavAndroid/1.0")
                }
                conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode in 200..299) {
                    body = readStreamSnippet(conn, 900_000); conn.disconnect()
                    if (body.isNotEmpty()) break
                } else conn.disconnect()
            } catch (_: Exception) {}
        }
        if (body.isEmpty()) return null
        return try {
            val elements = JSONObject(body).optJSONArray("elements") ?: return null
            val majorF = JSONArray(); val minorF = JSONArray()
            val majorSet = setOf("motorway", "trunk", "primary", "secondary", "tertiary")
            for (i in 0 until min(elements.length(), 1200)) {
                val el = elements.getJSONObject(i)
                val geom = el.optJSONArray("geometry") ?: continue
                if (geom.length() < 2) continue
                val hw = el.optJSONObject("tags")?.optString("highway", "residential") ?: "residential"
                val coords = JSONArray()
                for (j in 0 until geom.length()) {
                    val pt = geom.getJSONObject(j)
                    coords.put(JSONArray().put(pt.optDouble("lon")).put(pt.optDouble("lat")))
                }
                val feat = JSONObject().put("type", "Feature").put("properties", JSONObject()).put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
                if (majorSet.contains(hw)) majorF.put(feat) else minorF.put(feat)
            }
            JSONObject().put("majorRoadsGeoJson", JSONObject().put("type", "FeatureCollection").put("features", majorF).toString())
                .put("minorRoadsGeoJson", JSONObject().put("type", "FeatureCollection").put("features", minorF).toString()).toString()
        } catch (_: Exception) { null }
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
            val req = LocationRequest.Builder(priority, 1000L).setMinUpdateIntervalMillis(1000L).setMaxUpdateDelayMillis(1000L).build()
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
        statusText.text = "Status: Connecting to ${dev.name}..."
        toggleStreamButton.isEnabled = false
        ioExecutor.execute {
            try {
                try { bluetoothAdapter?.cancelDiscovery() } catch (_: SecurityException) {}
                val sock = dev.createRfcommSocketToServiceRecord(TelemetryProtocol.SERVICE_UUID)
                sock.connect()
                rfcommSocket = sock; dataOutputStream = DataOutputStream(sock.outputStream); isStreaming.set(true)
                runOnUiThread {
                    statusText.text = "Status: Connected & Streaming to ${dev.name}"
                    toggleStreamButton.text = "Stop Streaming"; toggleStreamButton.setBackgroundColor(0xFFDC2626.toInt()); toggleStreamButton.isEnabled = true
                }
                cachedRouteEnvelopeJson?.let { sendRouteDataPacket(it) }
                cachedMapRoadsEnvelopeJson?.let { sendMapRoadsPacket(it) }
            } catch (e: Throwable) {
                closeSocketQuietly()
                runOnUiThread {
                    statusText.text = "Status: Connection failed (${e.message})"
                    toggleStreamButton.text = "Connect & Start Streaming"; toggleStreamButton.setBackgroundColor(0xFF2563EB.toInt()); toggleStreamButton.isEnabled = true
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

    @Synchronized private fun sendMapRoadsPacket(json: String) {
        val out = dataOutputStream ?: return
        try { TelemetryProtocol.writeMapRoadsPacket(out, json) } catch (e: IOException) { stopStreamingOnDisconnect(e.message) }
    }

    private fun stopStreamingOnDisconnect(reason: String?) {
        isStreaming.set(false); closeSocketQuietly()
        runOnUiThread {
            statusText.text = "Status: Disconnected ($reason)"
            toggleStreamButton.text = "Connect & Start Streaming"; toggleStreamButton.setBackgroundColor(0xFF2563EB.toInt())
        }
    }

    private fun stopStreaming() {
        isStreaming.set(false)
        ioExecutor.execute {
            closeSocketQuietly()
            runOnUiThread {
                statusText.text = "Status: Streaming stopped."
                toggleStreamButton.text = "Connect & Start Streaming"; toggleStreamButton.setBackgroundColor(0xFF2563EB.toInt())
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
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (_: Throwable) {
            // WAKE_LOCK permission may not be present in manifest; FLAG_KEEP_SCREEN_ON keeps activity awake
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreaming.set(false)
        try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (_: Throwable) {}
        closeSocketQuietly()
        ioExecutor.shutdownNow(); routeExecutor.shutdownNow()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
    }
}
