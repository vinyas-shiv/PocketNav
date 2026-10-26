# PocketNav — Dual-Phone Offline 3D Navigation & Bluetooth RFCOMM Telemetry

**PocketNav** is an Android navigation system built in Kotlin that turns a secondary, offline Android phone (with **GPS OFF** and **Mobile Data OFF**) into a dedicated 3D cockpit navigation display powered over **Bluetooth Classic (RFCOMM)** by your primary phone locked in your pocket.

- **Latest Release**: [**PocketNav v.1.0-alpha**](../../releases/tag/v.1.0-alpha)
- **One-Tap APK Download**: [**`PocketNav-v1.0-alpha.apk`**](../../releases/download/v.1.0-alpha/PocketNav-v1.0-alpha.apk)
- **CI / Headless APK Builds**: [**GitHub Actions (`build.yml`)**](../../actions)

---

## Why PocketNav?

Keeping a primary smartphone mounted on a handlebar or dashboard in direct sunlight drains its battery, overheats the device, and exposes expensive hardware to vibration and weather. Meanwhile, an older spare phone mounted as a dedicated dashboard doesn't need its own SIM card, mobile data plan, or power-hungry GPS receiver.

**PocketNav** bridges the two devices over a low-latency Bluetooth RFCOMM socket:
1. **Phone 1 (Commander — Primary Phone in Your Pocket)**:
   - Keeps its screen **OFF** in your pocket while running a lightweight Android Foreground Service (`CommanderForegroundService`) with a CPU partial wake lock.
   - Intercepts shared destinations directly from the official **Google Maps** app via Android's native **Share (`ACTION_SEND`)** sheet.
   - Unshortens Google Maps share links, extracts the destination coordinates and place name, queries the **OSRM** driving route & turn-by-turn maneuver steps once, and beams the route envelope to Phone 2.
   - Streams compact **24-byte binary GPS telemetry frames** at 1 Hz over Bluetooth RFCOMM, automatically pausing UI redraws when the screen is off and throttling stationary packets to save battery.
2. **Phone 2 (Dashboard — Mounted Cockpit Display)**:
   - Operates **100% offline** (Airplane Mode ON, GPS OFF, Mobile Data OFF, Bluetooth ON).
   - Renders a hardware-accelerated **MapLibre Native 3D view** (`45°` camera tilt) with a dynamic 100-meter local reference grid, high-contrast blue route polyline, white turn maneuver markers, a red/white destination bullseye pin, and a 38-meter forward vehicle heading ray.
   - Displays a full **Automotive Navigation HUD**:
     - **Top Maneuver Banner**: Dynamic turn direction arrow (`↑`, `↱`, `↰`, `⤻`, `⤺`, `↻`, `↶`, `🏁`), live distance to the next turn, upcoming street name, and live Bluetooth connection status badge (`BT LIVE`).
     - **Bottom Cockpit Bar**: Live digital speedometer (`km/h`), remaining route distance, live ETA duration (`min`), estimated arrival clock time, destination title, and a **`3D FOLLOW` / `OVERVIEW`** camera mode toggle.

---

## How It Works in the Car or on the Bike

1. **Pair Both Phones Once** in Android Bluetooth Settings.
2. **Launch Dashboard on Phone 2**: Open **PocketNav** on your mounted secondary phone and tap **Start as Dashboard (Phone 2)**. It immediately enters full-screen 3D cockpit mode and listens for an incoming RFCOMM connection (`WAITING BT`).
3. **Start Commander on Phone 1**: Open **PocketNav** on your primary phone, select **Start as Commander (Phone 1)**, choose Phone 2 from the paired device dropdown, and tap **Connect & Start Pocket Streaming**.
   - *Tip*: Tap **Enable Unrestricted Pocket Battery Mode** once so Android never throttles GPS while Phone 1 is locked in your pocket.
4. **Share Any Place from Google Maps**:
   - Open the official **Google Maps** app on Phone 1, pick any destination, tap **Share**, and select **PocketNav Commander** (`Send Route to PocketNav`).
   - Phone 1 automatically resolves the link, fetches the OSRM driving route and turn maneuvers, and beams the route to Phone 2.
5. **Lock Phone 1 & Drive**: Put Phone 1 in your pocket with the screen turned off. Phone 2 smoothly animates along the blue route line at 1 Hz with live turn distances, speed, and ETA.

---

## Binary Telemetry & Route Protocol (`TelemetryProtocol`)

All communication over Bluetooth RFCOMM (`UUID: 8ce255c0-200a-11e0-ac64-0800200c9a66`) uses a framed binary protocol defined in [`MainActivity.kt`](app/src/main/java/com/pocketnav/app/MainActivity.kt):

| Packet Type | Header Byte | Framing / Payload | Description |
| :--- | :--- | :--- | :--- |
| **`PACKET_TYPE_LOCATION`** | `0x01` | Fixed `24 bytes` (`Big Endian`):<br>• `latitude` (`Double`, 8B)<br>• `longitude` (`Double`, 8B)<br>• `bearing` (`Float`, 4B)<br>• `speed` (`Float`, 4B, m/s) | 1 Hz high-accuracy GPS fix streamed from Phone 1 to Phone 2. |
| **`PACKET_TYPE_ROUTE_DATA`** | `0x02` | `4-byte Int` length prefix + UTF-8 JSON envelope (`routeGeoJson`, `turnsGeoJson`, `destGeoJson`, `destinationName`, `destLat`, `destLon`, `totalDistanceMeters`, `totalDurationSeconds`, `steps[]`) | Complete driving route geometry, turn maneuver points, and step metadata beamed on Google Maps Share hand-off. |

---

## Repository Structure

```text
PocketNav/
├── .github/workflows/
│   └── build.yml                  # Headless CI pipeline: builds APK & uploads to v.1.0-alpha Release
├── app/
│   ├── build.gradle.kts           # App module dependencies (MapLibre 11.8.0, Play Services Location 21.3.0)
│   └── src/main/
│       ├── AndroidManifest.xml    # Bluetooth, Location, Foreground Service & Google Maps ACTION_SEND intent filter
│       ├── java/com/pocketnav/app/
│       │   ├── MainActivity.kt        # Role selector & framed binary TelemetryProtocol
│       │   ├── CommanderActivity.kt   # Phone 1: Pocket foreground service, Google Maps unshortener & OSRM client
│       │   └── DashboardActivity.kt   # Phone 2: Offline 3D MapLibre renderer, camera animator & navigation HUD
│       └── res/layout/
│           ├── activity_main.xml      # Role selector UI
│           └── activity_dashboard.xml # Full-screen MapLibre view + Top Maneuver Banner & Bottom Cockpit HUD
├── build.gradle.kts               # Root Gradle configuration (AGP 8.5.2, Kotlin 1.9.24)
├── gradle.properties              # JVM heap & AndroidX properties
├── gradle/wrapper/
│   └── gradle-wrapper.properties  # Gradle 8.9 distribution config
├── gradlew                        # Self-bootstrapping Linux/macOS Gradle wrapper script
└── settings.gradle.kts            # Project repositories & module inclusion
```

---

## Building Without Android Studio (GitHub Actions)

This repository is designed to compile **completely headless** in the cloud without requiring a local Android Studio installation:

- Every push to `main` triggers [`.github/workflows/build.yml`](.github/workflows/build.yml) on `ubuntu-latest` with JDK 17.
- The `./gradlew` script automatically bootstraps `gradle-wrapper.jar` if absent and runs `./gradlew assembleDebug --stacktrace`.
- The resulting APK is published both as a workflow artifact (`PocketNav-debug-apk`) and directly attached to the [**`v.1.0-alpha` Release**](../../releases/tag/v.1.0-alpha) as **`PocketNav-v1.0-alpha.apk`**.
