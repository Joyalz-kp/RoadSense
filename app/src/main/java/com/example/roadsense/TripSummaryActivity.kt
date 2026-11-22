package com.example.roadsense

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.roadsense.data.Extras
import com.example.roadsense.data.Fmt
import com.example.roadsense.data.RoutePoint
import com.example.roadsense.data.Trip
import com.example.roadsense.data.TripRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.roadsense.ui.RoadScoreGauge
import com.example.roadsense.ui.RoadSenseScaffold
import com.example.roadsense.ui.theme.RoadSenseTheme

/** Trip summary for one trip, chosen by the trip ID passed in the Intent extra. */
class TripSummaryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val tripId = intent.getLongExtra(Extras.TRIP_ID, -1L)
        val trip = TripRepository.get(this).getTrip(tripId)
        if (trip == null) {
            finish()
            return
        }

        val date = Fmt.dateTime(trip.startTime)
        val duration = Fmt.duration(trip.durationMillis)
        val distance = Fmt.distance(trip.distanceMeters)

        setContent {
            RoadSenseTheme {
                TripSummaryScreen(
                    trip = trip,
                    date = date,
                    duration = duration,
                    distance = distance,
                    // Explicit intent: open the bump list for this trip
                    onViewBumps = {
                        startActivity(Intent(this, BumpDetailsActivity::class.java).putExtra(Extras.TRIP_ID, trip.id))
                    },
                    // Implicit intent: share the summary as text with any app (chooser)
                    onShare = {
                        val text = getString(
                            R.string.share_text,
                            date, duration, distance, trip.averageSpeedKmh, trip.bumpCount, trip.roadScore
                        )
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
                    }
                )
            }
        }
    }
}

/** Opens on the trip's route map; score, stats, bumps and share are behind a details button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripSummaryScreen(
    trip: Trip,
    date: String,
    duration: String,
    distance: String,
    onViewBumps: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    var showDetails by remember { mutableStateOf(false) }
    var routePoints by remember { mutableStateOf<List<RoutePoint>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(trip.id) {
        routePoints = withContext(Dispatchers.IO) {
            TripRepository.get(context).getRoutePoints(trip.id)
        }
        loaded = true
    }

    val hasRoute = routePoints.size >= 2

    if (showDetails) {
        RoadSenseScaffold(title = stringResource(R.string.trip_summary)) {
            Text(date, style = MaterialTheme.typography.bodyMedium)
            RoadScoreGauge(score = trip.roadScore, modifier = Modifier.padding(top = 16.dp))

            val statStyle = MaterialTheme.typography.bodyLarge
            Text(
                stringResource(R.string.summary_duration, duration),
                style = statStyle,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(stringResource(R.string.summary_distance, distance), style = statStyle, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.summary_avg_speed, trip.averageSpeedKmh), style = statStyle, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.summary_bumps, trip.bumpCount), style = statStyle, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.summary_score, trip.roadScore), style = statStyle, modifier = Modifier.padding(top = 8.dp))

            Spacer(Modifier.weight(1f))
            Button(onClick = onViewBumps, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.view_bumps))
            }
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.share))
            }
            OutlinedButton(
                onClick = { showDetails = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.show_map))
            }
        }
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.trip_summary)) }) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (loaded && hasRoute) {
                RoadMapView(
                    trips = listOf(trip),
                    routePoints = routePoints,
                    modifier = Modifier.fillMaxSize(),
                    fitCamera = true
                )
            } else if (loaded) {
                Text(
                    text = stringResource(R.string.map_no_trip_route),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                )
            }
            Button(
                onClick = { showDetails = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(stringResource(R.string.view_score_and_details))
            }
        }
    }
}
