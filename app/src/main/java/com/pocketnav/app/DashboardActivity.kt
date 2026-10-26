package com.pocketnav.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class DashboardActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private lateinit var tvTurnIcon: TextView
    private lateinit var tvNextTurnDistance: TextView
    private lateinit var tvNextTurnStreet: TextView
    private lateinit var tvDashboardStatus: TextView
    private lateinit var tvSpeedKmh: TextView
    private lateinit var tvTripEtaAndDistance: TextView
    private lateinit var tvDestinationAndArrival: TextView
    private lateinit var btnCameraMode: TextView

    private var minorRoadsGeoJsonSource: GeoJsonSource? = null
    private var majorRoadsGeoJsonSource: GeoJsonSource? = null
    private var routeGeoJsonSource: GeoJsonSource? = null
    private var turnsGeoJsonSource: GeoJsonSource? = null
    private var destGeoJsonSource: GeoJsonSource? = null
    private var headingRayGeoJsonSource: GeoJsonSource? = null
    private var vehicleGeoJsonSource: GeoJsonSource? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private val isListening = AtomicBoolean(false)
    private var listenerThread: Thread? = null

    @Volatile private var pendingRouteEnvelopeJson: String? = null
    @Volatile private var pendingMapRoadsEnvelopeJson: String? = null
    @Volatile private var hasReceivedFirstFix = false
    @Volatile private var isOverviewMode = false
    @Volatile private var lastKnownLat: Double = 12.9716
    @Volatile private var lastKnownLon: Double = 77.5946
    @Volatile private var lastKnownBearing: Float = 0.0f

    private data class RouteStep(val lat: Double, val lon: Double, val type: String, val modifier: String, val name: String, val distanceMeters: Double)
    private val routeCoords = mutableListOf<Pair<Double, Double>>()
    private val routeSteps = mutableListOf<RouteStep>()

    @Volatile private var activeDestinationName: String = "No active route"
    @Volatile private var activeDestLat: Double = Double.NaN
    @Volatile private var activeDestLon: Double = Double.NaN
    @Volatile private var routeTotalDistanceMeters: Double = 0.0
    @Volatile private var routeTotalDurationSeconds: Double = 0.0

    companion object {
        private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""
        private val OFFLINE_STYLE_JSON = """
            {"version":8,"name":"PocketNav Offline 3D","sources":{"offline-grid":{"type":"geojson","data":${buildGrid()}}},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#0B101B"}},{"id":"grid","type":"line","source":"offline-grid","paint":{"line-color":"#162032","line-width":1.0,"line-opacity":0.45}}]}
        """.trimIndent()

        private fun buildGrid(): String {
            val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
            var first = true
            for (lat in -80..80 step 10) {
                if (!first) sb.append(","); first = false
                sb.append("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[[-180,$lat],[180,$lat]]}}""")
            }
            for (lon in -180..180 step 10) {
                sb.append(""",{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[[$lon,-80],[$lon,80]]}}""")
            }
            return sb.append("]}").toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_dashboard)

        mapView = findViewById(R.id.mapView)
        tvTurnIcon = findViewById(R.id.tvTurnIcon)
        tvNextTurnDistance = findViewById(R.id.tvNextTurnDistance)
        tvNextTurnStreet = findViewById(R.id.tvNextTurnStreet)
        tvDashboardStatus = findViewById(R.id.tvDashboardStatus)
        tvSpeedKmh = findViewById(R.id.tvSpeedKmh)
        tvTripEtaAndDistance = findViewById(R.id.tvTripEtaAndDistance)
        tvDestinationAndArrival = findViewById(R.id.tvDestinationAndArrival)
        btnCameraMode = findViewById(R.id.btnCameraMode)

        btnCameraMode.setOnClickListener {
            isOverviewMode = !isOverviewMode
            if (isOverviewMode) {
                btnCameraMode.text = "OVERVIEW"; btnCameraMode.setTextColor(Color.parseColor("#FDE047"))
                zoomToOverviewCamera()
            } else {
                btnCameraMode.text = "3D FOLLOW"; btnCameraMode.setTextColor(Color.parseColor("#38BDF8"))
                animateToFollowCamera(lastKnownLat, lastKnownLon, lastKnownBearing)
            }
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.setStyle(Style.Builder().fromJson(OFFLINE_STYLE_JSON)) { style ->
                setupLayers(style)
                map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(lastKnownLat, lastKnownLon)).zoom(16.5).tilt(45.0).bearing(0.0).build()))
                pendingMapRoadsEnvelopeJson?.let { pendingMapRoadsEnvelopeJson = null; applyMapRoadsEnvelope(it) }
                pendingRouteEnvelopeJson?.let { pendingRouteEnvelopeJson = null; applyRouteDataEnvelope(it) }
            }
        }
        bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        startRfcommServerLoop()
    }

    private fun setupLayers(style: Style) {
        minorRoadsGeoJsonSource = GeoJsonSource("minor-roads-src", EMPTY_FC).also { style.addSource(it) }
        style.addLayer(LineLayer("minor-casing", "minor-roads-src").withProperties(PropertyFactory.lineColor(Color.parseColor("#080D1A")), PropertyFactory.lineWidth(5.5f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        style.addLayer(LineLayer("minor-fill", "minor-roads-src").withProperties(PropertyFactory.lineColor(Color.parseColor("#1E293B")), PropertyFactory.lineWidth(3.5f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))

        majorRoadsGeoJsonSource = GeoJsonSource("major-roads-src", EMPTY_FC).also { style.addSource(it) }
        style.addLayer(LineLayer("major-casing", "major-roads-src").withProperties(PropertyFactory.lineColor(Color.parseColor("#090E1A")), PropertyFactory.lineWidth(10.0f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        style.addLayer(LineLayer("major-fill", "major-roads-src").withProperties(PropertyFactory.lineColor(Color.parseColor("#334155")), PropertyFactory.lineWidth(6.5f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))

        routeGeoJsonSource = GeoJsonSource("route-src", EMPTY_FC).also { style.addSource(it) }
        style.addLayer(LineLayer("route-casing", "route-src").withProperties(PropertyFactory.lineColor(Color.parseColor("#185ABC")), PropertyFactory.lineWidth(14.0f), PropertyFactory.lineOpacity(0.95f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        style.addLayer(LineLayer("route-line", "route-src").withProperties(PropertyFactory.lineColor(Color.parseColor("#4285F4")), PropertyFactory.lineWidth(9.0f), PropertyFactory.lineOpacity(0.95f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))

        turnsGeoJsonSource = GeoJsonSource("turns-src", EMPTY_FC).also { style.addSource(it) }
        style.addLayer(CircleLayer("turns-layer", "turns-src").withProperties(PropertyFactory.circleRadius(4.5f), PropertyFactory.circleColor(Color.parseColor("#FFFFFF")), PropertyFactory.circleStrokeColor(Color.parseColor("#185ABC")), PropertyFactory.circleStrokeWidth(2.0f), PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)))

        destGeoJsonSource = GeoJsonSource("dest-src", EMPTY_FC).also { style.addSource(it) }
        style.addLayer(CircleLayer("dest-halo", "dest-src").withProperties(PropertyFactory.circleRadius(18.0f), PropertyFactory.circleColor(Color.parseColor("#EF4444")), PropertyFactory.circleOpacity(0.28f), PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)))
        style.addLayer(CircleLayer("dest-ring", "dest-src").withProperties(PropertyFactory.circleRadius(10.5f), PropertyFactory.circleColor(Color.parseColor("#FFFFFF")), PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)))
        style.addLayer(CircleLayer("dest-core", "dest-src").withProperties(PropertyFactory.circleRadius(6.0f), PropertyFactory.circleColor(Color.parseColor("#DC2626")), PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)))

        headingRayGeoJsonSource = GeoJsonSource("ray-src", EMPTY_FC).also { style.addSource(it) }
        style.addLayer(LineLayer("ray-layer", "ray-src").withProperties(PropertyFactory.lineColor(Color.parseColor("#38BDF8")), PropertyFactory.lineWidth(4.5f), PropertyFactory.lineOpacity(0.85f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND)))

        vehicleGeoJsonSource = GeoJsonSource("veh-src", EMPTY_FC).also { style.addSource(it) }
        style.addLayer(CircleLayer("veh-halo", "veh-src").withProperties(PropertyFactory.circleRadius(20.0f), PropertyFactory.circleColor(Color.parseColor("#4285F4")), PropertyFactory.circleOpacity(0.25f), PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)))
        style.addLayer(CircleLayer("veh-puck", "veh-src").withProperties(PropertyFactory.circleRadius(8.5f), PropertyFactory.circleColor(Color.parseColor("#38BDF8")), PropertyFactory.circleStrokeColor(Color.parseColor("#FFFFFF")), PropertyFactory.circleStrokeWidth(3.0f), PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)))
    }

    @SuppressLint("MissingPermission")
    private fun startRfcommServerLoop() {
        isListening.set(true)
        listenerThread = Thread {
            while (isListening.get()) {
                try {
                    val adapter = bluetoothAdapter
                    if (adapter == null || !adapter.isEnabled) {
                        updateBadge("BT OFF"); Thread.sleep(2000L); continue
                    }
                    updateBadge("WAITING BT")
                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(TelemetryProtocol.SERVICE_NAME, TelemetryProtocol.SERVICE_UUID)
                    val sock = serverSocket?.accept()
                    serverSocket?.close(); serverSocket = null
                    if (sock != null) {
                        clientSocket = sock; updateBadge("BT LIVE")
                        readPacketsLoop(sock)
                    }
                } catch (_: IOException) {
                    closeSocketsQuietly()
                    if (isListening.get()) try { Thread.sleep(1000L) } catch (_: InterruptedException) { break }
                } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun readPacketsLoop(socket: BluetoothSocket) {
        val input = DataInputStream(socket.inputStream)
        try {
            while (isListening.get() && socket.isConnected) {
                when (val pkt = TelemetryProtocol.readNextPacket(input)) {
                    is TelemetryProtocol.IncomingPacket.LocationFrame -> onTelemetryFrame(pkt)
                    is TelemetryProtocol.IncomingPacket.RouteDataFrame -> runOnUiThread {
                        if (routeGeoJsonSource != null) applyRouteDataEnvelope(pkt.geoJsonString) else pendingRouteEnvelopeJson = pkt.geoJsonString
                    }
                    is TelemetryProtocol.IncomingPacket.MapRoadsFrame -> runOnUiThread {
                        if (majorRoadsGeoJsonSource != null) applyMapRoadsEnvelope(pkt.roadsEnvelopeJson) else pendingMapRoadsEnvelopeJson = pkt.roadsEnvelopeJson
                    }
                }
            }
        } catch (_: EOFException) {} catch (_: IOException) {} finally {
            closeClientQuietly(); updateBadge("RECONNECTING")
        }
    }

    private fun updateBadge(status: String) {
        runOnUiThread {
            tvDashboardStatus.text = status
            tvDashboardStatus.setTextColor(Color.parseColor(if (status == "BT LIVE") "#34D399" else if (status == "BT OFF") "#F87171" else "#38BDF8"))
        }
    }

    private fun applyRouteDataEnvelope(payload: String) {
        try {
            val root = JSONObject(payload)
            if (root.has("routeGeoJson")) {
                val rGeo = root.getString("routeGeoJson")
                routeGeoJsonSource?.setGeoJson(rGeo)
                turnsGeoJsonSource?.setGeoJson(root.optString("turnsGeoJson", EMPTY_FC))
                destGeoJsonSource?.setGeoJson(root.optString("destGeoJson", EMPTY_FC))
                activeDestinationName = root.optString("destinationName", "Destination")
                activeDestLat = root.optDouble("destLat", Double.NaN)
                activeDestLon = root.optDouble("destLon", Double.NaN)
                routeTotalDistanceMeters = root.optDouble("totalDistanceMeters", 0.0)
                routeTotalDurationSeconds = root.optDouble("totalDurationSeconds", 0.0)
                parseRouteCoords(rGeo)
                routeSteps.clear()
                root.optJSONArray("steps")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.getJSONObject(i)
                        routeSteps.add(RouteStep(s.optDouble("lat"), s.optDouble("lon"), s.optString("type", "turn"), s.optString("modifier", "straight"), s.optString("name", "Road"), s.optDouble("distance")))
                    }
                }
                if (!hasReceivedFirstFix && routeCoords.isNotEmpty()) {
                    val (fLat, fLon) = routeCoords.first()
                    lastKnownLat = fLat; lastKnownLon = fLon
                    updateVehicleAndRay(fLat, fLon, 0.0f)
                    mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(fLat, fLon)).zoom(16.5).tilt(45.0).bearing(0.0).build()), 900)
                }
                updateHud(lastKnownLat, lastKnownLon, 0.0f)
            } else {
                routeGeoJsonSource?.setGeoJson(payload)
                parseRouteCoords(payload)
            }
        } catch (_: Exception) {}
    }

    private fun parseRouteCoords(geoJson: String) {
        routeCoords.clear()
        try {
            val fc = JSONObject(geoJson)
            val geom = fc.optJSONArray("features")?.optJSONObject(0)?.optJSONObject("geometry") ?: fc
            val coords = geom.optJSONArray("coordinates") ?: return
            for (i in 0 until coords.length()) {
                val pt = coords.getJSONArray(i)
                routeCoords.add(Pair(pt.optDouble(1), pt.optDouble(0)))
            }
        } catch (_: Exception) {}
    }

    private fun applyMapRoadsEnvelope(json: String) {
        try {
            val env = JSONObject(json)
            majorRoadsGeoJsonSource?.setGeoJson(env.optString("majorRoadsGeoJson", EMPTY_FC))
            minorRoadsGeoJsonSource?.setGeoJson(env.optString("minorRoadsGeoJson", EMPTY_FC))
        } catch (_: Exception) {}
    }

    private fun onTelemetryFrame(frame: TelemetryProtocol.IncomingPacket.LocationFrame) {
        hasReceivedFirstFix = true
        lastKnownLat = frame.latitude; lastKnownLon = frame.longitude; lastKnownBearing = frame.bearing
        runOnUiThread {
            updateVehicleAndRay(frame.latitude, frame.longitude, frame.bearing)
            updateHud(frame.latitude, frame.longitude, frame.speed)
            if (!isOverviewMode) animateToFollowCamera(frame.latitude, frame.longitude, frame.bearing)
        }
    }

    private fun animateToFollowCamera(lat: Double, lon: Double, bearing: Float) {
        mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(lat, lon)).zoom(16.8).tilt(45.0).bearing(bearing.toDouble()).build()), 950)
    }

    private fun zoomToOverviewCamera() {
        if (routeCoords.size >= 2) {
            val (mLat, mLon) = routeCoords[routeCoords.size / 2]
            val span = computeRemainingMeters(routeCoords.first().first, routeCoords.first().second)
            val z = if (span > 25_000) 11.2 else if (span > 10_000) 12.4 else if (span > 4_000) 13.5 else 14.5
            mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(mLat, mLon)).zoom(z).tilt(0.0).bearing(0.0).build()), 800)
        }
    }

    private fun updateVehicleAndRay(lat: Double, lon: Double, bearing: Float) {
        vehicleGeoJsonSource?.setGeoJson(String.format(Locale.US, """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[%.6f,%.6f]}}]}""", lon, lat))
        val (tLat, tLon) = projectCoord(lat, lon, bearing.toDouble(), 38.0)
        headingRayGeoJsonSource?.setGeoJson(String.format(Locale.US, """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[[%.6f,%.6f],[%.6f,%.6f]]}}]}""", lon, lat, tLon, tLat))
    }

    private fun updateHud(lat: Double, lon: Double, speedMps: Float) {
        tvSpeedKmh.text = (speedMps * 3.6f).roundToInt().coerceAtLeast(0).toString()
        if (routeCoords.isEmpty()) return
        val next = findNextStep(lat, lon)
        if (next != null) {
            val (step, dist) = next
            tvTurnIcon.text = symbolFor(step.type, step.modifier)
            tvNextTurnDistance.text = formatDist(dist)
            tvNextTurnStreet.text = actionFor(step.type, step.modifier, step.name)
        } else {
            val d = if (!activeDestLat.isNaN()) haversine(lat, lon, activeDestLat, activeDestLon) else 0.0
            tvTurnIcon.text = "🏁"; tvNextTurnDistance.text = formatDist(d); tvNextTurnStreet.text = activeDestinationName
        }
        val remMeters = computeRemainingMeters(lat, lon)
        val spd = if (speedMps > 2.5f) speedMps.toDouble() else if (routeTotalDistanceMeters > 1.0 && routeTotalDurationSeconds > 1.0) routeTotalDistanceMeters / routeTotalDurationSeconds else 9.7
        val remSec = (remMeters / spd).coerceAtLeast(0.0)
        val remMin = (remSec / 60.0).roundToInt().coerceAtLeast(1)
        val arrTime = SimpleDateFormat("h:mm a", Locale.US).format(Date(System.currentTimeMillis() + (remSec * 1000.0).toLong()))
        tvTripEtaAndDistance.text = String.format(Locale.US, "%d min • %s", remMin, formatDist(remMeters))
        tvDestinationAndArrival.text = String.format(Locale.US, "Arrive %s • %s", arrTime, activeDestinationName)
    }

    private fun findNextStep(lat: Double, lon: Double): Pair<RouteStep, Double>? {
        if (routeSteps.isEmpty()) return null
        var idx = 0; var minD = Double.MAX_VALUE
        for (i in routeSteps.indices) {
            val d = haversine(lat, lon, routeSteps[i].lat, routeSteps[i].lon)
            if (d < minD) { minD = d; idx = i }
        }
        val pick = if (minD < 30.0 && idx + 1 < routeSteps.size) idx + 1 else if (idx == 0 && routeSteps[0].type == "depart" && routeSteps.size > 1) 1 else idx
        val s = routeSteps[pick]
        return Pair(s, haversine(lat, lon, s.lat, s.lon))
    }

    private fun computeRemainingMeters(lat: Double, lon: Double): Double {
        if (routeCoords.isEmpty()) return 0.0
        var idx = 0; var minD = Double.MAX_VALUE
        for (i in routeCoords.indices) {
            val d = haversine(lat, lon, routeCoords[i].first, routeCoords[i].second)
            if (d < minD) { minD = d; idx = i }
        }
        var sum = minD
        for (i in idx until routeCoords.size - 1) {
            sum += haversine(routeCoords[i].first, routeCoords[i].second, routeCoords[i + 1].first, routeCoords[i + 1].second)
        }
        return sum
    }

    private fun symbolFor(type: String, mod: String): String = when {
        type == "arrive" -> "🏁"
        type == "roundabout" || type == "rotary" -> "↻"
        type == "uturn" || mod.contains("uturn") -> "↶"
        mod.contains("right") && mod.contains("slight") -> "⤻"
        mod.contains("right") -> "↱"
        mod.contains("left") && mod.contains("slight") -> "⤺"
        mod.contains("left") -> "↰"
        else -> "↑"
    }

    private fun actionFor(type: String, mod: String, name: String): String {
        val r = if (name.isBlank() || name == "unnamed road") "road ahead" else name
        return when {
            type == "arrive" -> "Arrive at $activeDestinationName"
            type == "roundabout" || type == "rotary" -> "Roundabout onto $r"
            mod.contains("right") -> "Turn right onto $r"
            mod.contains("left") -> "Turn left onto $r"
            mod.contains("uturn") -> "Make a U-turn on $r"
            else -> "Continue on $r"
        }
    }

    private fun formatDist(m: Double): String = if (m >= 1000.0) String.format(Locale.US, "%.1f km", m / 1000.0) else String.format(Locale.US, "%d m", ((m / 10.0).roundToInt() * 10).coerceAtLeast(10))

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun projectCoord(lat: Double, lon: Double, brngDeg: Double, distM: Double): Pair<Double, Double> {
        val dByR = distM / 6371000.0; val brng = Math.toRadians(brngDeg)
        val lat1 = Math.toRadians(lat); val lon1 = Math.toRadians(lon)
        val lat2 = asin(sin(lat1) * cos(dByR) + cos(lat1) * sin(dByR) * cos(brng))
        val lon2 = lon1 + atan2(sin(brng) * sin(dByR) * cos(lat1), cos(dByR) - sin(lat1) * sin(lat2))
        return Pair(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun closeClientQuietly() { try { clientSocket?.close() } catch (_: IOException) {}; clientSocket = null }
    private fun closeSocketsQuietly() { closeClientQuietly(); try { serverSocket?.close() } catch (_: IOException) {}; serverSocket = null }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
  override fun onDestroy() { isListening.set(false); closeSocketsQuietly(); listenerThread?.interrupt(); mapView.onDestroy(); super.onDestroy() }
}
