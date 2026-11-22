package com.example.roadsense

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.roadsense.data.Extras
import com.example.roadsense.data.Fmt
import com.example.roadsense.data.RoadScore
import com.example.roadsense.data.RoutePoint
import com.example.roadsense.data.Trip
import com.example.roadsense.data.TripRepository
import com.example.roadsense.ui.RoadSenseScaffold
import com.example.roadsense.ui.theme.RoadSenseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.infowindow.InfoWindow

/** List of past trips, read from the Room database. */
class HistoryActivity : ComponentActivity() {

    private lateinit var repo: TripRepository

    // Compose state: replacing the list recomposes HistoryScreen.
    private var trips by mutableStateOf<List<Trip>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = TripRepository.get(this)

        setContent {
            RoadSenseTheme {
                HistoryScreen(
                    trips = trips,
                    // Explicit intent: open the tapped trip's summary, passing its ID as an extra
                    onTripClick = { trip ->
                        startActivity(Intent(this, TripSummaryActivity::class.java).putExtra(Extras.TRIP_ID, trip.id))
                    },
                    onOpenMap = { startActivity(Intent(this, RoadMapActivity::class.java)) },
                    // Runs only after the user confirms in the dialog
                    onDeleteAll = {
                        repo.deleteAll()
                        loadTrips()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadTrips()
    }

    private fun loadTrips() {
        trips = repo.getAllTrips()
    }
}

/** Trip list (tap a trip to open it) and a Delete all data button with a confirmation dialog. */
@Composable
fun HistoryScreen(
    trips: List<Trip>,
    onTripClick: (Trip) -> Unit,
    onOpenMap: () -> Unit,
    onDeleteAll: () -> Unit
) {
    // Whether the delete confirmation dialog is showing (kept across rotation).
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    RoadSenseScaffold(title = stringResource(R.string.trip_history)) {
        if (trips.isEmpty()) {
            Text(stringResource(R.string.no_trips), modifier = Modifier.padding(top = 8.dp))
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(trips, key = { it.id }) { trip ->
                ListItem(
                    headlineContent = { Text(Fmt.dateTime(trip.startTime)) },
                    supportingContent = {
                        Text("${Fmt.distance(trip.distanceMeters)}  |  ${trip.bumpCount} bumps  |  score ${trip.roadScore}")
                    },
                    modifier = Modifier.clickable { onTripClick(trip) }
                )
                HorizontalDivider()
            }
        }
        OutlinedButton(
            onClick = onOpenMap,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Text(stringResource(R.string.road_map))
        }
        Button(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) {
            Text(stringResource(R.string.delete_all_data))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDeleteAll()
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/** All trips on one OpenStreetMap, each route coloured by that trip's final road score. */
class RoadMapActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoadSenseTheme { RoadMapScreen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoadMapScreen() {
    val context = LocalContext.current
    var trips by remember { mutableStateOf<List<Trip>>(emptyList()) }
    var routePoints by remember { mutableStateOf<List<RoutePoint>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            val repo = TripRepository.get(context)
            repo.getAllTrips() to repo.getAllRoutePoints()
        }
        trips = result.first
        routePoints = result.second
        loaded = true
    }

    val hasData = routePoints.isNotEmpty()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.road_map)) }) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            RoadMapView(
                trips = trips,
                routePoints = routePoints,
                modifier = Modifier.fillMaxSize(),
                fitCamera = loaded
            )
            if (loaded && !hasData) {
                Text(
                    text = stringResource(R.string.map_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                )
            }
        }
    }
}

/**
 * Embeds an osmdroid [MapView] in Compose. Each trip is one polyline from start to end,
 * coloured by that trip's final road score (red below 50, amber 50-89, green from 90 up).
 */
@Composable
fun RoadMapView(
    trips: List<Trip>,
    routePoints: List<RoutePoint>,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    fitCamera: Boolean = true
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraFitted by remember { mutableStateOf(false) }
    val mapHolder = remember { arrayOfNulls<MapView>(1) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val mapView = mapHolder[0] ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapHolder[0]?.let { mapView ->
                mapView.onPause()
                mapView.onDetach()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // User-agent must be set before the MapView constructor, or OSM tiles return 403.
            prepareOsmdroid(ctx)
            MapView(ctx).apply {
                // Re-apply after construction in case anything reset the stored agent.
                applyOsmUserAgent(Configuration.getInstance())
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(interactive)
                if (!interactive) {
                    setOnTouchListener { _, _ -> true }
                }
                mapHolder[0] = this
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    onResume()
                }
            }
        },
        update = { view ->
            populateMap(view, trips, routePoints)
            if (fitCamera && !cameraFitted) {
                frameMap(view, routePoints)
                cameraFitted = true
            }
        }
    )
}

private const val OSM_USER_AGENT = "RoadSense/1.0 (github.com/Joyalz-kp/RoadSense)"
private const val PREF_PURGED_BLOCKED_TILES = "roadsense_purged_blocked_tiles_v2"

/** Load osmdroid preferences, then set the HTTP user-agent before a [MapView] is created. */
private fun prepareOsmdroid(context: Context) {
    val app = context.applicationContext
    val prefs = app.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
    val config = Configuration.getInstance()
    // load() first: it can overwrite the user-agent with a stored or package-name value.
    config.load(app, prefs)
    applyOsmUserAgent(config)
    config.save(app, prefs)
    // OSM can return a 200 image that says "Access blocked 403". Purge any that were
    // cached under the old user-agent. New pref key so this runs again after the last purge.
    if (!prefs.getBoolean(PREF_PURGED_BLOCKED_TILES, false)) {
        try {
            SqlTileWriter().purgeCache()
        } catch (_: Exception) {
            config.osmdroidTileCache?.listFiles()?.forEach { it.deleteRecursively() }
        }
        prefs.edit().putBoolean(PREF_PURGED_BLOCKED_TILES, true).apply()
    }
}

/**
 * MAPNIK uses FLAG_USER_AGENT_NORMALIZED, so tile downloads send getNormalizedUserAgent()
 * (packageName/version, e.g. com.example.roadsense/1) and ignore userAgentValue.
 * That placeholder package is what OSM was blocking. Set the meaningful agent on every
 * path the downloader can use.
 */
private fun applyOsmUserAgent(config: org.osmdroid.config.IConfigurationProvider) {
    config.userAgentValue = OSM_USER_AGENT
    config.additionalHttpRequestProperties["User-Agent"] = OSM_USER_AGENT
    try {
        var type: Class<*>? = config.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField("mNormalizedUserAgent")
                field.isAccessible = true
                field.set(config, OSM_USER_AGENT)
                break
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
    } catch (_: Exception) {
        // userAgentValue and the extra HTTP header are still set
    }
}

private fun populateMap(
    mapView: MapView,
    trips: List<Trip>,
    routePoints: List<RoutePoint>
) {
    InfoWindow.closeAllInfoWindowsOn(mapView)
    mapView.overlays.removeAll { it is Polyline }
    val pointsByTrip = routePoints.groupBy { it.tripId }
    val context = mapView.context
    for (trip in trips) {
        // Time order so the line runs from the first saved fix to the last.
        val pts = pointsByTrip[trip.id].orEmpty().sortedBy { it.timestamp }
        if (pts.size < 2) continue
        val geo = pts.map { GeoPoint(it.latitude, it.longitude) }
        val line = Polyline(mapView)
        line.outlinePaint.color = RoadScore.color(trip.roadScore)
        line.outlinePaint.strokeWidth = 12f
        line.outlinePaint.strokeCap = Paint.Cap.ROUND
        line.outlinePaint.strokeJoin = Paint.Join.ROUND
        line.setPoints(geo)
        line.title = context.getString(R.string.map_trip_info, Fmt.dateTime(trip.startTime), trip.roadScore)
        mapView.overlays.add(line)
    }
    mapView.invalidate()
}

private fun frameMap(mapView: MapView, routePoints: List<RoutePoint>) {
    val geo = ArrayList<GeoPoint>(routePoints.size)
    for (point in routePoints) geo.add(GeoPoint(point.latitude, point.longitude))
    when {
        geo.size >= 2 -> mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo), false, 80)
        geo.size == 1 -> {
            mapView.controller.setZoom(16.0)
            mapView.controller.setCenter(geo[0])
        }
        else -> {
            val fallback = lastKnownGeoPoint(mapView.context) ?: DEFAULT_MAP_CENTER
            mapView.controller.setZoom(12.0)
            mapView.controller.setCenter(fallback)
        }
    }
    mapView.invalidate()
}

private fun lastKnownGeoPoint(context: Context): GeoPoint? {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { it in manager.allProviders }
    val location = providers.mapNotNull { provider ->
        try {
            manager.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            null
        }
    }.maxByOrNull { it.time }
    return location?.let { GeoPoint(it.latitude, it.longitude) }
}

private val DEFAULT_MAP_CENTER = GeoPoint(53.3498, -6.2603)
