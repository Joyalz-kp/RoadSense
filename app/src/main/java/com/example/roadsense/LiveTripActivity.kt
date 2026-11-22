package com.example.roadsense

import android.Manifest
import android.app.Application
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
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import com.example.roadsense.data.Extras
import com.example.roadsense.data.Fmt
import com.example.roadsense.data.RoadScore
import com.example.roadsense.data.TripRepository
import com.example.roadsense.ui.RoadScoreGauge
import com.example.roadsense.ui.RoadSenseScaffold
import com.example.roadsense.ui.theme.RoadSenseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Live trip screen. The activity owns the sensors (linear acceleration or accelerometer, and GPS);
 * [LiveTripViewModel] holds the UI state and does the Room work off the main thread.
 */
class LiveTripActivity : ComponentActivity(), SensorEventListener, LocationListener {

    private val viewModel: LiveTripViewModel by viewModels()
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager

    private var sensorListening = false
    private var locationListening = false

    private val gravity = FloatArray(3)
    private var gravityReady = false

    // User interaction: runtime location permission request.
    // Android 12+ ignores a request for FINE location on its own, so ask for FINE and COARSE together.
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                // Register only if the screen is in the started state, so a late grant
                // cannot start GPS after onStop. onStart will pick it up if needed.
                if (!viewModel.ended && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    startLocationUpdates()
                }
            } else if (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                // Approximate location is too coarse for bump positions; bumps are still counted.
                viewModel.updateGpsStatus(R.string.status_precise_needed)
            } else {
                viewModel.updateGpsStatus(R.string.status_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        setContent {
            RoadSenseTheme {
                LiveTripScreen(
                    viewModel = viewModel,
                    onEndTrip = { endTrip() }
                )
            }
        }
        // Pressing Back also ends (and saves) the trip.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = endTrip()
        })

        // Sensors are started in onStart. Ask for location permission once here.
        if (!hasLocationPermission()) {
            locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (viewModel.ended) return
        startAccelerometer()
        if (hasLocationPermission()) startLocationUpdates()
    }

    override fun onStop() {
        // Unregister so nothing keeps running in the background, then write the
        // current totals to the existing trip row. onDestroy is not guaranteed.
        stopSensors()
        viewModel.persistTrip()
        super.onStop()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startAccelerometer() {
        if (sensorListening) return
        // Prefer Android's gravity-free linear acceleration sensor; fall back to the raw
        // accelerometer with a low-pass gravity estimate on devices that do not have it.
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor != null && sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)) {
            sensorListening = true
            viewModel.updateAccelStatus(R.string.status_active)
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (locationListening) return
        if (!hasLocationPermission()) {
            viewModel.updateGpsStatus(R.string.status_permission_denied)
            return
        }
        // Use satellite GPS for accurate distance and speed, plus network location (Wi-Fi and
        // mobile signal), which gives a position within seconds, even indoors.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in locationManager.allProviders }
        // Some devices and emulators have no location provider at all.
        if (providers.isEmpty()) {
            viewModel.updateGpsStatus(R.string.status_unavailable)
            return
        }
        try {
            // Registered even if a provider is switched off, so turning it on mid-trip starts giving fixes.
            for (provider in providers) {
                locationManager.requestLocationUpdates(provider, 1000L, 0f, this)
            }
            locationListening = true
            // Start with a recent known position (under 2 minutes old) so the first bumps get a location.
            // Keep a fix already collected during this trip; do not replace it on resume.
            if (viewModel.bumpLocation == null) {
                providers.mapNotNull { locationManager.getLastKnownLocation(it) }
                    .filter { System.currentTimeMillis() - it.time < 2 * 60 * 1000L }
                    .maxByOrNull { it.time }
                    ?.let { viewModel.setInitialBumpLocation(it) }
            }
        } catch (e: SecurityException) {
            viewModel.updateGpsStatus(R.string.status_permission_denied)
            return
        }
        if (viewModel.bumpLocation != null) {
            viewModel.updateGpsStatus(R.string.status_active)
            return
        }
        viewModel.updateGpsStatus(
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                R.string.status_waiting_fix
            } else {
                R.string.status_gps_off
            }
        )
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
        viewModel.onLinearAcceleration(sqrt(lx * lx + ly * ly + lz * lz))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // GPS: trip distance, current speed and bump locations
    override fun onLocationChanged(location: Location) {
        viewModel.onLocationChanged(location)
    }

    @Deprecated("Required on API < 30")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) viewModel.onGpsProviderEnabled()
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) viewModel.onGpsProviderDisabled()
    }

    private fun stopSensors() {
        if (sensorListening) {
            sensorManager.unregisterListener(this)
            sensorListening = false
        }
        if (locationListening) {
            locationManager.removeUpdates(this)
            locationListening = false
        }
    }

    private fun endTrip() {
        if (viewModel.ended) return
        stopSensors()
        viewModel.endTrip { tripId ->
            // Explicit intent: open the summary for this trip, passing its ID as an extra
            startActivity(Intent(this, TripSummaryActivity::class.java).putExtra(Extras.TRIP_ID, tripId))
            finish()
            // finish() will also run onStop, which persists the same row again and is harmless.
        }
    }

    override fun onDestroy() {
        // Safety net only: onStop normally already unregistered the sensors and saved.
        stopSensors()
        viewModel.persistTrip()
        super.onDestroy()
    }
}

/**
 * Holds live-trip UI state and talks to Room on a background dispatcher.
 * The activity forwards sensor and location events here; the composable only reads state
 * and sends the End trip event up.
 */
class LiveTripViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TripRepository.get(application)
    private val tripReady = CompletableDeferred<Long>()
    private val dbMutex = Mutex()

    var tripId = -1L
        private set
    val startTime: Long = System.currentTimeMillis()
    private var severitySum = 0
    private var lastLocation: Location? = null
    private var lastFixAt = 0L
    private var currentSpeedMps = 0f
    var bumpLocation: Location? = null
        private set
    private var lastBumpTime = 0L
    private var lastRoutePoint: Location? = null
    var ended = false
        private set
    var isPaused by mutableStateOf(false)
        private set
    // Total time spent paused, not including an in-progress pause.
    private var pausedAccumulatedMs = 0L
    private var pauseStartedAt = 0L

    var bumpCount by mutableIntStateOf(0)
        private set
    var lastSeverity by mutableIntStateOf(0) // 0 = no bump yet, 1 light, 2 medium, 3 severe
        private set
    var speedKmh by mutableFloatStateOf(0f)
        private set
    var distanceMeters by mutableDoubleStateOf(0.0)
        private set
    var roadScore by mutableIntStateOf(100)
        private set
    var accelStatus by mutableIntStateOf(R.string.status_unavailable)
        private set
    var gpsStatus by mutableIntStateOf(R.string.status_waiting_fix)
        private set
    // Ticks once a second so the elapsed trip time counts up on screen.
    var now by mutableLongStateOf(startTime)
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = withContext(NonCancellable) { repo.startTrip(startTime) }
                tripId = id
                tripReady.complete(id)
            } catch (e: Exception) {
                tripReady.completeExceptionally(e)
            }
        }
        viewModelScope.launch {
            while (true) {
                if (!isPaused && !ended) now = System.currentTimeMillis()
                delay(1000L)
            }
        }
        refreshScore()
    }

    fun updateAccelStatus(@StringRes status: Int) {
        accelStatus = status
    }

    fun updateGpsStatus(@StringRes status: Int) {
        gpsStatus = status
    }

    fun setInitialBumpLocation(location: Location) {
        if (bumpLocation == null) bumpLocation = location
    }

    fun togglePause() {
        if (ended) return
        val at = System.currentTimeMillis()
        if (isPaused) {
            pausedAccumulatedMs += at - pauseStartedAt
            pauseStartedAt = 0L
            // Drop the last GPS point so distance does not jump by the pause gap.
            lastLocation = null
            lastFixAt = 0L
            currentSpeedMps = 0f
            speedKmh = 0f
            isPaused = false
            now = at
        } else {
            pauseStartedAt = at
            lastLocation = null
            lastFixAt = 0L
            currentSpeedMps = 0f
            speedKmh = 0f
            now = at
            isPaused = true
        }
    }

    /** Elapsed time shown on screen: wall time minus time spent paused. */
    fun elapsedMs(): Long = maxOf(0L, now - startTime - pausedAccumulatedMs)

    /** Active time up to now, including an in-progress pause. Used when saving. */
    private fun activeElapsedMs(): Long {
        val at = System.currentTimeMillis()
        var paused = pausedAccumulatedMs
        if (isPaused && pauseStartedAt > 0L) paused += at - pauseStartedAt
        return maxOf(0L, at - startTime - paused)
    }

    fun onLinearAcceleration(magnitude: Float) {
        if (ended || isPaused) return
        val eventTime = System.currentTimeMillis()
        if (magnitude >= BUMP_THRESHOLD && eventTime - lastBumpTime >= BUMP_COOLDOWN_MS) {
            // Ignore shakes while stopped or when GPS speed is stale; don't start the cooldown.
            if (!isVehicleMoving(eventTime)) return
            lastBumpTime = eventTime
            val severity = when {
                magnitude >= SEVERE_THRESHOLD -> 3
                magnitude >= MEDIUM_THRESHOLD -> 2
                else -> 1
            }
            val loc = bumpLocation
            bumpCount++
            lastSeverity = severity
            severitySum += severity
            refreshScore()
            viewModelScope.launch(Dispatchers.IO) {
                val id = try {
                    tripReady.await()
                } catch (_: Exception) {
                    return@launch
                }
                dbMutex.withLock {
                    repo.addBump(id, eventTime, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0, severity)
                }
            }
        }
    }

    fun onLocationChanged(location: Location) {
        if (ended) return
        if (isPaused) {
            // Ignore movement while paused and forget the last point so resume does not jump.
            lastLocation = null
            return
        }
        // Any fix, including a rough network one, is good enough to mark where a bump happened.
        bumpLocation = location
        gpsStatus = R.string.status_active
        lastFixAt = System.currentTimeMillis()
        val accurate = !location.hasAccuracy() || location.accuracy <= MAX_FIX_ACCURACY_M
        val speed = speedMpsFrom(location, if (accurate) lastLocation else null)
        if (speed != null) {
            currentSpeedMps = speed
            speedKmh = speed * 3.6f
        }
        // Distance only uses accurate fixes, so rough network positions don't add fake distance.
        if (!accurate) return
        lastLocation?.let { distanceMeters += it.distanceTo(location) }
        lastLocation = location
        refreshScore()
        maybeSaveRoutePoint(location)
    }

    /** GPS speed in m/s, or distance/time between consecutive fixes when the fix has no speed. */
    private fun speedMpsFrom(location: Location, previous: Location?): Float? {
        if (location.hasSpeed()) return location.speed
        if (previous == null) return null
        val dtMs = if (location.elapsedRealtimeNanos > 0L && previous.elapsedRealtimeNanos > 0L) {
            (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000L
        } else {
            location.time - previous.time
        }
        if (dtMs <= 0L) return null
        return previous.distanceTo(location) / (dtMs / 1000f)
    }

    /** True when a fix in the last 5 s shows speed of at least [MIN_SPEED_MPS]. */
    fun isVehicleMoving(at: Long = System.currentTimeMillis()): Boolean {
        if (lastFixAt == 0L || at - lastFixAt > STALE_FIX_MS) return false
        return currentSpeedMps >= MIN_SPEED_MPS
    }

    private fun maybeSaveRoutePoint(location: Location) {
        // Always keep the first accurate fix, even if later spacing is 20 m.
        if (!shouldSaveRoutePoint(location, force = false)) return
        lastRoutePoint = Location(location)
        val lat = location.latitude
        val lng = location.longitude
        val time = if (location.time > 0L) location.time else System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            val id = try {
                tripReady.await()
            } catch (_: Exception) {
                return@launch
            }
            dbMutex.withLock {
                repo.addRoutePoint(id, time, lat, lng)
            }
        }
    }

    /** True for the first point, or when the fix is at least 20 m from the last saved one, or when forced. */
    private fun shouldSaveRoutePoint(location: Location, force: Boolean): Boolean {
        val previous = lastRoutePoint
        if (previous == null) return true
        if (previous.latitude == location.latitude && previous.longitude == location.longitude) return false
        if (force) return true
        return previous.distanceTo(location) >= ROUTE_MIN_SPACING_M
    }

    /** Appends the last accurate fix so the drawn road reaches the trip's end point. */
    private fun saveLastRoutePointLocked(tripId: Long) {
        val location = lastLocation ?: return
        if (location.hasAccuracy() && location.accuracy > MAX_FIX_ACCURACY_M) return
        if (!shouldSaveRoutePoint(location, force = true)) return
        lastRoutePoint = Location(location)
        val time = if (location.time > 0L) location.time else System.currentTimeMillis()
        repo.addRoutePoint(tripId, time, location.latitude, location.longitude)
    }

    fun onGpsProviderEnabled() {
        gpsStatus = R.string.status_waiting_fix
    }

    fun onGpsProviderDisabled() {
        gpsStatus = R.string.status_gps_off
        currentSpeedMps = 0f
        speedKmh = 0f
    }

    /** Recalculates the live road score; the gauge redraws because roadScore is Compose state. */
    private fun refreshScore() {
        roadScore = RoadScore.compute(distanceMeters, severitySum)
    }

    /** Writes the current totals onto the existing trip row. Safe to call more than once. */
    fun persistTrip() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) { persistNow() }
        }
    }

    fun endTrip(onSaved: (Long) -> Unit) {
        if (ended) return
        ended = true
        viewModelScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) { persistNow() }
            val id = tripId
            withContext(Dispatchers.Main) {
                if (id > 0L) onSaved(id)
            }
        }
    }

    private suspend fun persistNow() {
        val id = try {
            tripReady.await()
        } catch (_: Exception) {
            return
        }
        if (id <= 0L) return
        dbMutex.withLock {
            saveLastRoutePointLocked(id)
            val score = RoadScore.compute(distanceMeters, severitySum)
            // Store an endTime such that endTime - startTime equals active (unpaused) time.
            // The live duration excludes pauses; this keeps the saved duration in line
            // without a schema change. endTime is not shown as a clock time.
            repo.finishTrip(id, startTime + activeElapsedMs(), distanceMeters, bumpCount, score)
        }
    }

    private companion object {
        const val BUMP_THRESHOLD = 4.0f // m/s^2 gravity-free acceleration magnitude
        const val MEDIUM_THRESHOLD = 8.0f
        const val SEVERE_THRESHOLD = 12.0f
        const val BUMP_COOLDOWN_MS = 1000L
        const val MAX_FIX_ACCURACY_M = 50f
        const val ROUTE_MIN_SPACING_M = 20f
        const val MIN_SPEED_MPS = 2.0f // about 7 km/h; below this the vehicle is treated as stopped
        const val STALE_FIX_MS = 5000L
    }
}

/**
 * Live dashboard: sensor status, bump counter and last bump severity, speed, distance,
 * elapsed time, road-score gauge, Pause/Resume and End trip.
 */
@Composable
fun LiveTripScreen(
    viewModel: LiveTripViewModel,
    onEndTrip: () -> Unit
) {
    val severityText = when (viewModel.lastSeverity) {
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
                stringResource(viewModel.accelStatus),
                stringResource(viewModel.gpsStatus)
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = viewModel.bumpCount.toString(),
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(stringResource(R.string.bump_counter_label), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(R.string.last_bump_format, stringResource(severityText)),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(R.string.speed_format, viewModel.speedKmh),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            text = stringResource(
                if (viewModel.isVehicleMoving(viewModel.now)) {
                    R.string.status_moving
                } else {
                    R.string.status_stopped_bumps_paused
                }
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(R.string.summary_distance, Fmt.distance(viewModel.distanceMeters)),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = stringResource(
                R.string.summary_duration,
                Fmt.duration(viewModel.elapsedMs())
            ),
            style = MaterialTheme.typography.bodyLarge
        )
        RoadScoreGauge(score = viewModel.roadScore, modifier = Modifier.padding(top = 12.dp))
        Text(stringResource(R.string.road_score_label), style = MaterialTheme.typography.bodyMedium)
        if (viewModel.isPaused) {
            Text(
                text = stringResource(R.string.status_paused),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // Pushes the Pause/Resume and End trip buttons to the bottom of the screen.
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { viewModel.togglePause() },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(if (viewModel.isPaused) R.string.resume else R.string.pause))
            }
            Button(onClick = onEndTrip, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.end_trip))
            }
        }
    }
}
