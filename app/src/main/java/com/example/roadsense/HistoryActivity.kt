package com.example.roadsense

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.roadsense.data.Extras
import com.example.roadsense.data.Fmt
import com.example.roadsense.data.Trip
import com.example.roadsense.data.TripRepository
import com.example.roadsense.ui.RoadSenseScaffold
import com.example.roadsense.ui.theme.RoadSenseTheme

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
