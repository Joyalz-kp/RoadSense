package com.example.roadsense

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.roadsense.data.Bump
import com.example.roadsense.data.Extras
import com.example.roadsense.data.Fmt
import com.example.roadsense.data.TripRepository
import com.example.roadsense.ui.RoadSenseScaffold
import com.example.roadsense.ui.theme.RoadSenseTheme

/** Every bump recorded on one trip, chosen by the trip ID passed in the Intent extra. */
class BumpDetailsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val tripId = intent.getLongExtra(Extras.TRIP_ID, -1L)
        val bumps = TripRepository.get(this).getBumps(tripId)

        setContent {
            RoadSenseTheme {
                BumpDetailsScreen(bumps = bumps, onOpenInMaps = { openInMaps(it) })
            }
        }
    }

    /** Implicit intent: show the bump's location in any installed maps app (geo: URI). */
    private fun openInMaps(bump: Bump) {
        if (!bump.hasLocation) return
        val uri = Uri.parse("geo:${bump.latitude},${bump.longitude}?q=${bump.latitude},${bump.longitude}(Bump)")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_maps_app, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Bump list: time, severity and location, with an Open in Maps button on each bump. */
@Composable
fun BumpDetailsScreen(bumps: List<Bump>, onOpenInMaps: (Bump) -> Unit) {
    RoadSenseScaffold(title = stringResource(R.string.bump_details)) {
        if (bumps.isEmpty()) {
            Text(stringResource(R.string.no_bumps), modifier = Modifier.padding(top = 8.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(bumps, key = { it.id }) { bump ->
                val where = if (bump.hasLocation) {
                    String.format("%.5f, %.5f", bump.latitude, bump.longitude)
                } else {
                    "location unknown"
                }
                ListItem(
                    headlineContent = { Text("${Fmt.time(bump.timestamp)}  |  ${severityLabel(bump.severity)}") },
                    supportingContent = { Text(where) },
                    trailingContent = {
                        // Disabled when the bump has no GPS position.
                        TextButton(onClick = { onOpenInMaps(bump) }, enabled = bump.hasLocation) {
                            Text(stringResource(R.string.open_in_maps))
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

/** Severity 1-3 from the bump detector as a word. */
private fun severityLabel(severity: Int) = when (severity) {
    3 -> "severe"
    2 -> "medium"
    else -> "minor"
}
