package com.example.roadsense

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.roadsense.data.Extras
import com.example.roadsense.data.Fmt
import com.example.roadsense.data.RoadScore
import com.example.roadsense.data.TripRepository
import com.example.roadsense.ui.RoadScoreGauge
import com.example.roadsense.ui.RoadSenseScaffold
import com.example.roadsense.ui.theme.RoadSenseTheme
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/**
 * Live trip screen. The activity owns the sensors (linear acceleration or accelerometer, and GPS) and the trip data;
 * the values the screen shows are Compose state, so the UI redraws whenever they change.
 */
class LiveTripActivity : ComponentActivity(), SensorEventListener, LocationListener {

    private lateinit var repo: TripRepository
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager

    private var tripId = -1L
    private var startTime = 0L
    private var severitySum = 0
    private var lastLocation: Location? = null
    // Most recent position from any provider (GPS or network); used to place bumps.
    private var bumpLocation: Location? = null
    private var lastBumpTime = 0L
    private var ended = false

    private val gravity = FloatArray(3)
    private var gravityReady = false

    // --- Compose state shown on screen (changing any of these recomposes LiveTripScreen) ---
    private var bumpCount by mutableIntStateOf(0)
    private var lastSeverity by mutableIntStateOf(0) // 0 = no bump yet, 1 light, 2 medium, 3 severe
    private var speedKmh by mutableFloatStateOf(0f)
    private var distanceMeters by mutableDoubleStateOf(0.0)
    private var roadScore by mutableIntStateOf(100)
    private var accelStatus by mutableIntStateOf(R.string.status_unavailable)
    private var gpsStatus by mutableIntStateOf(R.string.status_waiting_fix)

    // User interaction: runtime location permission request.
    // Android 12+ ignores a request for FINE location on its own, so ask for FINE and COARSE together.
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                startLocationUpdates()
            } else if (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                // Approximate location is too coarse for bump positions; bumps are still counted.
                gpsStatus = R.string.status_precise_needed
            } else {
                gpsStatus = R.string.status_permission_denied
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repo = TripRepository.get(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Create the trip row first so the screen has a valid start time to count from.
        startTime = System.currentTimeMillis()
        tripId = repo.startTrip(startTime)

        setContent {
            RoadSenseTheme {
                LiveTripScreen(
                    accelStatus = accelStatus,
                    gpsStatus = gpsStatus,
                    startTime = startTime,
                    bumpCount = bumpCount,
                    lastSeverity = lastSeverity,
                    speedKmh = speedKmh,
                    distanceMeters = distanceMeters,
                    roadScore = roadScore,
                    onEndTrip = { endTrip() }
                )
            }
        }
        // Pressing Back also ends (and saves) the trip.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = endTrip()
        })

        startAccelerometer()
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
        updateUi()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startAccelerometer() {
        // Prefer Android's gravity-free linear acceleration sensor; fall back to the raw
        // accelerometer with a low-pass gravity estimate on devices that do not have it.
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor != null && sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)) {
            accelStatus = R.string.status_active
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            gpsStatus = R.string.status_permission_denied
            return
        }
        // Use satellite GPS for accurate distance and speed, plus network location (Wi-Fi and
        // mobile signal), which gives a position within seconds, even indoors.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in locationManager.allProviders }
        // Some devices and emulators have no location provider at all.
        if (providers.isEmpty()) {
            gpsStatus = R.string.status_unavailable
            return
        }
        try {
            // Registered even if a provider is switched off, so turning it on mid-trip starts giving fixes.
            for (provider in providers) {
                locationManager.requestLocationUpdates(provider, 1000L, 0f, this)
            }
            // Start with a recent known position (under 2 minutes old) so the first bumps get a location.
            providers.mapNotNull { locationManager.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time < 2 * 60 * 1000L }
                .maxByOrNull { it.time }
                ?.let { bumpLocation = it }
        } catch (e: SecurityException) {
            gpsStatus = R.string.status_permission_denied
            return
        }
        if (bumpLocation != null) {
            gpsStatus = R.string.status_active
            return
        }
        gpsStatus = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            R.string.status_waiting_fix
        } else {
            R.string.status_gps_off
        }
    }

    // Motion sensor: use gravity-free acceleration (TYPE_LINEAR_ACCELERATION, or the
    // raw accelerometer with a low-pass gravity estimate) and take the 3-axis magnitude.
    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val lx: Float
        val ly: Float
        val lz: Float
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            lx = v[0]
            ly = v[1]
            lz = v[2]
        } else {
            if (!gravityReady) {
                for (i in 0..2) gravity[i] = v[i]
                gravityReady = true
                return
            }
            val alpha = 0.9f
            for (i in 0..2) gravity[i] = alpha * gravity[i] + (1 - alpha) * v[i]
            lx = v[0] - gravity[0]
            ly = v[1] - gravity[1]
            lz = v[2] - gravity[2]
        }
        val magnitude = sqrt(lx * lx + ly * ly + lz * lz)

        val now = System.currentTimeMillis()
        if (magnitude >= BUMP_THRESHOLD && now - lastBumpTime >= BUMP_COOLDOWN_MS) {
            lastBumpTime = now
            val severity = when {
                magnitude >= SEVERE_THRESHOLD -> 3
                magnitude >= MEDIUM_THRESHOLD -> 2
                else -> 1
            }
            // Without a GPS fix the bump is still saved, with 0,0 meaning "no location".
            val loc = bumpLocation
            repo.addBump(tripId, now, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0, severity)
            bumpCount++
            lastSeverity = severity
            severitySum += severity
            updateUi()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // GPS: trip distance, current speed and bump locations
    override fun onLocationChanged(location: Location) {
        // Any fix, including a rough network one, is good enough to mark where a bump happened.
        bumpLocation = location
        gpsStatus = R.string.status_active
        // Distance and speed only use accurate fixes, so rough network positions don't add fake distance.
        if (location.hasAccuracy() && location.accuracy > MAX_FIX_ACCURACY_M) return
        lastLocation?.let { distanceMeters += it.distanceTo(location) }
        lastLocation = location
        speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        updateUi()
    }

    @Deprecated("Required on API < 30")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) gpsStatus = R.string.status_waiting_fix
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            gpsStatus = R.string.status_gps_off
            speedKmh = 0f
        }
    }

    /** Recalculates the live road score; the gauge redraws because roadScore is Compose state. */
    private fun updateUi() {
        roadScore = RoadScore.compute(distanceMeters, severitySum)
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    /** Stops the sensors and saves the trip to Room exactly once. */
    private fun saveTrip() {
        if (ended) return
        ended = true
        stopSensors()
        val score = RoadScore.compute(distanceMeters, severitySum)
        repo.finishTrip(tripId, System.currentTimeMillis(), distanceMeters, bumpCount, score)
    }

    private fun endTrip() {
        if (ended) return
        saveTrip()
        // Explicit intent: open the summary for this trip, passing its ID as an extra
        startActivity(Intent(this, TripSummaryActivity::class.java).putExtra(Extras.TRIP_ID, tripId))
        finish()
    }

    override fun onDestroy() {
        // If the screen is closed some other way (not End trip or Back), still keep the trip.
        if (!ended) saveTrip()
        super.onDestroy()
    }

    private companion object {
        const val BUMP_THRESHOLD = 4.0f // m/s^2 gravity-free acceleration magnitude
        const val MEDIUM_THRESHOLD = 8.0f
        const val SEVERE_THRESHOLD = 12.0f
        const val BUMP_COOLDOWN_MS = 1000L
        const val MAX_FIX_ACCURACY_M = 50f
    }
}

/**
 * Live dashboard: sensor status, bump counter and last bump severity, speed, distance,
 * elapsed time, road-score gauge and End trip.
 */
@Composable
fun LiveTripScreen(
    @StringRes accelStatus: Int,
    @StringRes gpsStatus: Int,
    startTime: Long,
    bumpCount: Int,
    lastSeverity: Int,
    speedKmh: Float,
    distanceMeters: Double,
    roadScore: Int,
    onEndTrip: () -> Unit
) {
    // Clock that ticks once a second so the elapsed trip time counts up on screen.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val severityText = when (lastSeverity) {
        3 -> R.string.severity_severe
        2 -> R.string.severity_medium
        1 -> R.string.severity_light
        else -> R.string.severity_none
    }

    RoadSenseScaffold(
        title = stringResource(R.string.live_trip),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(
                R.string.sensor_status_format,
                stringResource(accelStatus),
                stringResource(gpsStatus)
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = bumpCount.toString(),
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(stringResource(R.string.bump_counter_label), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(R.string.last_bump_format, stringResource(severityText)),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(R.string.speed_format, speedKmh),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            text = stringResource(R.string.summary_distance, Fmt.distance(distanceMeters)),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = stringResource(R.string.summary_duration, Fmt.duration(maxOf(0L, now - startTime))),
            style = MaterialTheme.typography.bodyLarge
        )
        RoadScoreGauge(score = roadScore, modifier = Modifier.padding(top = 12.dp))
        Text(stringResource(R.string.road_score_label), style = MaterialTheme.typography.bodyMedium)

        // Pushes the End trip button to the bottom of the screen.
        Spacer(Modifier.weight(1f))
        Button(onClick = onEndTrip, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.end_trip))
        }
    }
}
