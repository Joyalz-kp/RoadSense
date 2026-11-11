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
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.roadsense.data.Extras
import com.example.roadsense.data.RoadScore
import com.example.roadsense.data.TripRepository
import kotlin.math.abs
import kotlin.math.sqrt

class LiveTripActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    private lateinit var repo: TripRepository
    private lateinit var tvStatus: TextView
    private lateinit var tvBumpCount: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var gauge: RoadScoreGaugeView

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager

    private var tripId = -1L
    private var startTime = 0L
    private var bumpCount = 0
    private var severitySum = 0
    private var distanceMeters = 0.0
    private var lastLocation: Location? = null
    private var lastBumpTime = 0L
    private var speedKmh = 0f
    private var ended = false

    private val gravity = FloatArray(3)
    private var gravityReady = false

    private var accelStatus = R.string.status_unavailable
    private var gpsStatus = R.string.status_waiting_fix

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startLocationUpdates()
            } else {
                gpsStatus = R.string.status_permission_denied
                updateStatus()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_live_trip)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvStatus = findViewById(R.id.tvSensorStatus)
        tvBumpCount = findViewById(R.id.tvBumpCount)
        tvSpeed = findViewById(R.id.tvSpeed)
        gauge = findViewById(R.id.gaugeScore)
        findViewById<Button>(R.id.btnEndTrip).setOnClickListener { endTrip() }
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
        updateStatus()
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
            updateStatus()
            return
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        gpsStatus = R.string.status_waiting_fix
        updateStatus()
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
        updateStatus()
        updateUi()
    }

    @Deprecated("Required on API < 30")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    private fun updateStatus() {
        tvStatus.text = getString(R.string.sensor_status_format, getString(accelStatus), getString(gpsStatus))
    }

    private fun updateUi() {
        tvBumpCount.text = bumpCount.toString()
        tvSpeed.text = getString(R.string.speed_format, speedKmh)
        gauge.score = RoadScore.compute(distanceMeters, severitySum)
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
