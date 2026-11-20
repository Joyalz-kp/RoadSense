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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.roadsense.data.Extras
import com.example.roadsense.data.RoadScore
import com.example.roadsense.data.TripRepository
import com.example.roadsense.ui.RoadScoreGauge
import com.example.roadsense.ui.RoadSenseScaffold
import com.example.roadsense.ui.theme.RoadSenseTheme
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Live trip screen. The activity owns the sensors (accelerometer and GPS) and the trip data;
 * the values the screen shows are Compose state, so the UI redraws whenever they change.
 */
class LiveTripActivity : ComponentActivity(), SensorEventListener, LocationListener {

    private lateinit var repo: TripRepository
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager

    private var tripId = -1L
    private var startTime = 0L
    private var severitySum = 0
    private var distanceMeters = 0.0
    private var lastLocation: Location? = null
    private var lastBumpTime = 0L
    private var ended = false

    private val gravity = FloatArray(3)
    private var gravityReady = false

    // --- Compose state shown on screen (changing any of these recomposes LiveTripScreen) ---
    private var bumpCount by mutableIntStateOf(0)
    private var speedKmh by mutableFloatStateOf(0f)
    private var roadScore by mutableIntStateOf(100)
    private var accelStatus by mutableIntStateOf(R.string.status_unavailable)
    private var gpsStatus by mutableIntStateOf(R.string.status_waiting_fix)

    // User interaction: runtime location permission request
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startLocationUpdates()
            } else {
                gpsStatus = R.string.status_permission_denied
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoadSenseTheme {
                LiveTripScreen(
                    accelStatus = accelStatus,
                    gpsStatus = gpsStatus,
                    bumpCount = bumpCount,
                    speedKmh = speedKmh,
                    roadScore = roadScore,
                    onEndTrip = { endTrip() }
                )
            }
        }
        // Pressing Back also ends (and saves) the trip.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = endTrip()
        })

        repo = TripRepository.get(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        startTime = System.currentTimeMillis()
        tripId = repo.startTrip(startTime)

        startAccelerometer()
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        updateUi()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startAccelerometer() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel != null && sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)) {
            accelStatus = R.string.status_active
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            gpsStatus = R.string.status_unavailable
            return
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        gpsStatus = R.string.status_waiting_fix
    }

    // Accelerometer: estimate gravity with a low-pass filter, remove it, and keep the
    // component of the remaining jolt along the gravity (vertical) direction.
    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (!gravityReady) {
            for (i in 0..2) gravity[i] = v[i]
            gravityReady = true
            return
        }
        val alpha = 0.9f
        for (i in 0..2) gravity[i] = alpha * gravity[i] + (1 - alpha) * v[i]
        val lx = v[0] - gravity[0]
        val ly = v[1] - gravity[1]
        val lz = v[2] - gravity[2]
        val gNorm = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (gNorm < 1f) return
        val vertical = abs((lx * gravity[0] + ly * gravity[1] + lz * gravity[2]) / gNorm)

        val now = System.currentTimeMillis()
        if (vertical >= BUMP_THRESHOLD && now - lastBumpTime >= BUMP_COOLDOWN_MS) {
            lastBumpTime = now
            val severity = when {
                vertical >= SEVERE_THRESHOLD -> 3
                vertical >= MEDIUM_THRESHOLD -> 2
                else -> 1
            }
            val loc = lastLocation
            repo.addBump(tripId, now, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0, severity)
            bumpCount++
            severitySum += severity
            updateUi()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // GPS: trip distance and bump locations
    override fun onLocationChanged(location: Location) {
        if (location.hasAccuracy() && location.accuracy > MAX_FIX_ACCURACY_M) return
        gpsStatus = R.string.status_active
        lastLocation?.let { distanceMeters += it.distanceTo(location) }
        lastLocation = location
        speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        updateUi()
    }

    @Deprecated("Required on API < 30")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    /** Recalculates the live road score; the gauge redraws because roadScore is Compose state. */
    private fun updateUi() {
        roadScore = RoadScore.compute(distanceMeters, severitySum)
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    private fun endTrip() {
        if (ended) return
        ended = true
        stopSensors()
        val score = RoadScore.compute(distanceMeters, severitySum)
        repo.finishTrip(tripId, System.currentTimeMillis(), distanceMeters, bumpCount, score)
        // Explicit intent: open the summary for this trip, passing its ID as an extra
        startActivity(Intent(this, TripSummaryActivity::class.java).putExtra(Extras.TRIP_ID, tripId))
        finish()
    }

    override fun onDestroy() {
        if (!ended) stopSensors()
        super.onDestroy()
    }

    private companion object {
        const val BUMP_THRESHOLD = 4.0f // m/s^2 vertical jolt with gravity removed
        const val MEDIUM_THRESHOLD = 8.0f
        const val SEVERE_THRESHOLD = 12.0f
        const val BUMP_COOLDOWN_MS = 1000L
        const val MAX_FIX_ACCURACY_M = 50f
    }
}

/** Live dashboard: sensor status, bump counter, speed, road-score gauge and End trip. */
@Composable
fun LiveTripScreen(
    @StringRes accelStatus: Int,
    @StringRes gpsStatus: Int,
    bumpCount: Int,
    speedKmh: Float,
    roadScore: Int,
    onEndTrip: () -> Unit
) {
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
            modifier = Modifier.padding(top = 32.dp)
        )
        Text(stringResource(R.string.bump_counter_label), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(R.string.speed_format, speedKmh),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        RoadScoreGauge(score = roadScore, modifier = Modifier.padding(top = 32.dp))
        Text(stringResource(R.string.road_score_label), style = MaterialTheme.typography.bodyMedium)

        // Pushes the End trip button to the bottom of the screen.
        Spacer(Modifier.weight(1f))
        Button(onClick = onEndTrip, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.end_trip))
        }
    }
}
