package com.pocketnav.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class DashboardActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null

    private val isListening = AtomicBoolean(false)
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null

    private var hasInitializedGrid = false
    private var pendingRouteGeoJson: String? = null

    companion object {
        private const val ROUTE_SOURCE_ID = "route-source"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        setContentView(R.layout.activity_dashboard)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isTiltGesturesEnabled = true
            map.uiSettings.isRotateGesturesEnabled = true

            map.setStyle(Style.Builder().fromJson(buildDarkBaseStyleJson())) { style ->
                updateOfflineReferenceGrid(style, 13.066125, 77.506451)

                pendingRouteGeoJson?.let { geoJson ->
                    style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)?.setGeoJson(geoJson)
                    pendingRouteGeoJson = null
                }

                val initialCam = CameraPosition.Builder()
                    .target(LatLng(13.066125, 77.506451))
                    .zoom(16.5)
                    .tilt(45.0)
                    .bearing(0.0)
                    .build()
                map.moveCamera(CameraUpdateFactory.newCameraPosition(initialCam))
            }
        }

        startBluetoothRfcommListener()
    }

    private fun buildDarkBaseStyleJson(): String = """
        {
          "version": 8,
          "name": "PocketNav Dark 3D Style",
          "sources": {
            "grid-source": {
              "type": "geojson",
              "data": { "type": "FeatureCollection", "features": [] }
            },
            "route-source": {
              "type": "geojson",
              "data": { "type": "FeatureCollection", "features": [] }
            },
            "puck-source": {
              "type": "geojson",
              "data": { "type": "FeatureCollection", "features": [] }
            }
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": {
                "background-color": "#0B1221"
              }
            },
            {
              "id": "grid-layer",
              "type": "line",
              "source": "grid-source",
              "paint": {
                "line-color": "#1E293B",
                "line-width": 1.5
              }
            },
            {
              "id": "route-casing-layer",
              "type": "line",
              "source": "route-source",
              "layout": {
                "line-cap": "round",
                "line-join": "round"
              },
              "paint": {
                "line-color": "#185ABC",
                "line-width": 14.0,
                "line-opacity": 0.65
              }
            },
            {
              "id": "route-line-layer",
              "type": "line",
              "source": "route-source",
              "layout": {
                "line-cap": "round",
                "line-join": "round"
              },
              "paint": {
                "line-color": "#4285F4",
                "line-width": 9.5,
                "line-opacity": 0.90
              }
            },
            {
              "id": "puck-halo-layer",
              "type": "circle",
              "source": "puck-source",
              "paint": {
                "circle-radius": 18.0,
                "circle-color": "#4285F4",
                "circle-opacity": 0.25,
                "circle-pitch-alignment": "map"
              }
            },
            {
              "id": "puck-core-layer",
              "type": "circle",
              "source": "puck-source",
              "paint": {
                "circle-radius": 8.5,
                "circle-color": "#4285F4",
                "circle-stroke-width": 3.0,
                "circle-stroke-color": "#FFFFFF",
                "circle-pitch-alignment": "map"
              }
            }
          ]
        }
    """.trimIndent()

    private fun updateOfflineReferenceGrid(style: Style, centerLat: Double, centerLon: Double) {
        val gridSource = style.getSourceAs<GeoJsonSource>("grid-source") ?: return
        val step = 0.001
        val span = 35
        val features = StringBuilder()
        for (i in -span..span) {
            val lat = centerLat + i * step
            if (features.isNotEmpty()) features.append(",")
            features.append(
                String.format(
                    Locale.US,
                    "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[%.6f,%.6f],[%.6f,%.6f]]}}",
                    centerLon - span * step, lat, centerLon + span * step, lat
                )
            )
        }
        for (j in -span..span) {
            val lon = centerLon + j * step
            features.append(",")
            features.append(
                String.format(
                    Locale.US,
                    "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[%.6f,%.6f],[%.6f,%.6f]]}}",
                    lon, centerLat - span * step, lon, centerLat + span * step
                )
            )
        }
        gridSource.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[$features]}")
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothRfcommListener() {
        val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return
        if (!btAdapter.isEnabled) return

        isListening.set(true)
        Thread {
            val gpsBuffer = ByteArray(TelemetryProtocol.GPS_PAYLOAD_SIZE_BYTES)
            while (isListening.get()) {
                try {
                    val srv = btAdapter.listenUsingRfcommWithServiceRecord(
                        TelemetryProtocol.SERVICE_NAME,
                        TelemetryProtocol.SERVICE_UUID
                    )
                    serverSocket = srv
                    val socket = srv.accept()
                    clientSocket = socket
                    try {
                        srv.close()
                    } catch (_: IOException) {
                    }

                    val dataIn = DataInputStream(socket.inputStream)
                    while (isListening.get() && socket.isConnected) {
                        val packetType = dataIn.readByte()
                        when (packetType) {
                            TelemetryProtocol.PACKET_TYPE_GPS -> {
                                dataIn.readFully(gpsBuffer, 0, TelemetryProtocol.GPS_PAYLOAD_SIZE_BYTES)
                                val frame = TelemetryProtocol.decodeGpsPayload(gpsBuffer)
                                runOnUiThread {
                                    onGpsTelemetryReceived(frame)
                                }
                            }
                            TelemetryProtocol.PACKET_TYPE_ROUTE_DATA -> {
                                val length = dataIn.readInt()
                                if (length in 1..(5 * 1024 * 1024)) {
                                    val routeBytes = ByteArray(length)
                                    dataIn.readFully(routeBytes, 0, length)
                                    val geoJsonString = String(routeBytes, StandardCharsets.UTF_8)
                                    runOnUiThread {
                                        onRouteGeoJsonReceived(geoJsonString)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: IOException) {
                    closeSockets()
                    try {
                        Thread.sleep(1000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }.start()
    }

    private fun onRouteGeoJsonReceived(geoJsonString: String) {
        pendingRouteGeoJson = geoJsonString
        val map = mapLibreMap ?: return
        map.getStyle { style ->
            style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)?.setGeoJson(geoJsonString)
        }
    }

    private fun onGpsTelemetryReceived(frame: TelemetryProtocol.TelemetryFrame) {
        if (frame.latitude.isNaN() || frame.longitude.isNaN()) return
        val map = mapLibreMap ?: return

        map.getStyle { style ->
            if (!hasInitializedGrid) {
                hasInitializedGrid = true
                updateOfflineReferenceGrid(style, frame.latitude, frame.longitude)
            }

            val puckJson = String.format(
                Locale.US,
                "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[%.6f,%.6f]}}]}",
                frame.longitude,
                frame.latitude
            )
            style.getSourceAs<GeoJsonSource>("puck-source")?.setGeoJson(puckJson)
        }

        val cameraPosition = CameraPosition.Builder()
            .target(LatLng(frame.latitude, frame.longitude))
            .zoom(16.5)
            .tilt(45.0)
            .bearing(frame.bearing.toDouble())
            .build()

        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(cameraPosition),
            850
        )
    }

    private fun closeSockets() {
        try {
            clientSocket?.close()
        } catch (_: IOException) {
        }
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        clientSocket = null
        serverSocket = null
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        isListening.set(false)
        closeSockets()
        mapView.onDestroy()
        super.onDestroy()
    }
}
